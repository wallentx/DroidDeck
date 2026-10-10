import java.io.*;
import java.lang.reflect.*;

/** Disposable host capability probe: no root disk, no networking, 512 MiB. */
public final class AvfGpuProbe {
    static Class<?> type(String name) throws Exception {
        return Class.forName(name);
    }
    static Object call(Object obj, String name, Class<?> arg, Object value) throws Exception {
        try {
            return obj.getClass().getMethod(name, arg).invoke(obj, value);
        } catch (NoSuchMethodException absent) {
            if (arg != boolean.class)
                throw absent;
            return obj.getClass().getMethod(name, Boolean.class).invoke(obj, value);
        }
    }
    static boolean verified(String log) {
        if (log.contains("Kernel panic") || log.contains("DROIDDECK_VIRTGPU_FAIL_V1")
            || log.contains("DROIDDECK_VIRTGPU_CI_2D_V1"))
            return false;
        for (String marker :
            new String[] {"DROIDDECK_VIRTGPU_BEGIN_V1", "DROIDDECK_VIRTGPU_NODE_V1",
                "DROIDDECK_GFXSTREAM_CONTEXT_V1", "DROIDDECK_VIRTGPU_PASS_V1"}) {
            int count = 0;
            for (String line : log.split("\\r?\\n"))
                if (line.equals(marker))
                    count++;
            if (count != 1)
                return false;
        }
        return true;
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !args[0].matches("/data/local/tmp/droiddeck-gpu-[0-9a-f]{32}"))
            throw new IllegalArgumentException("isolated staging directory required");
        final File base = new File(args[0]);
        Thread watchdog = new Thread(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(45000);
                } catch (InterruptedException e) {
                    return;
                }
                System.err.println("Probe deadline");
                System.exit(124);
            }
        });
        watchdog.setDaemon(true);
        watchdog.start();
        Object owner = null, vm = null, kernel = null, initrd = null, console = null;
        boolean passed = false;
        Class<?> iface = type("android.system.virtualizationservice.IVirtualMachine");
        try {
            type("android.os.Looper").getMethod("prepareMainLooper").invoke(null);
            Class<?> at = type("android.app.ActivityThread");
            Object thread = at.getDeclaredMethod("systemMain").invoke(null);
            Object context = at.getDeclaredMethod("getSystemContext").invoke(thread);
            Class<?> platform = type("android.system.virtualmachine.VirtualizationService");
            Method getInstance = platform.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            owner = getInstance.invoke(null);
            Method getBinder = platform.getDeclaredMethod("getBinder");
            getBinder.setAccessible(true);
            Object service = getBinder.invoke(owner);
            String customName = "android.system.virtualmachine.VirtualMachineCustomImageConfig";
            Object custom = type(customName + "$Builder").getConstructor().newInstance();
            call(custom, "setName", String.class, "droiddeck-gpu-probe");
            call(custom, "setKernelPath", String.class, new File(base, "Image").getPath());
            call(custom, "setInitrdPath", String.class,
                new File(base, "probe-initramfs.cpio.gz").getPath());
            call(custom, "addParam", String.class, "console=hvc0 rdinit=/init panic=-1");
            Object display =
                type(customName + "$DisplayConfig$Builder").getConstructor().newInstance();
            call(display, "setWidth", int.class, 1280);
            call(display, "setHeight", int.class, 720);
            call(custom, "setDisplayConfig", type(customName + "$DisplayConfig"),
                display.getClass().getMethod("build").invoke(display));
            Object gpu = type(customName + "$GpuConfig$Builder").getConstructor().newInstance();
            call(gpu, "setBackend", String.class, "gfxstream");
            call(gpu, "setRendererUseEgl", boolean.class, false);
            call(gpu, "setRendererUseGles", boolean.class, false);
            call(gpu, "setRendererUseGlx", boolean.class, false);
            call(gpu, "setRendererUseSurfaceless", boolean.class, true);
            call(gpu, "setRendererUseVulkan", boolean.class, true);
            call(gpu, "setContextTypes", String[].class,
                new String[] {"gfxstream-vulkan", "gfxstream-composer"});
            call(gpu, "setRendererFeatures", String.class,
                "VulkanDisableCoherentMemoryAndEmulate:enabled;VulkanAllocateHostVisibleAsUdmabuf:"
                + "enabled;ExternalBlob:enabled");
            call(custom, "setGpuConfig", type(customName + "$GpuConfig"),
                gpu.getClass().getMethod("build").invoke(gpu));
            Object builder = type("android.system.virtualmachine.VirtualMachineConfig$Builder")
                                 .getConstructor(type("android.content.Context"))
                                 .newInstance(context);
            call(builder, "setCustomImageConfig", type(customName),
                custom.getClass().getMethod("build").invoke(custom));
            call(builder, "setProtectedVm", boolean.class, false);
            call(builder, "setMemoryBytes", long.class, 512L * 1024 * 1024);
            Object cfg = builder.getClass().getMethod("build").invoke(builder);
            Method convert = cfg.getClass().getDeclaredMethod("toVsRawConfig");
            convert.setAccessible(true);
            Object raw = convert.invoke(cfg);
            for (Field f : raw.getClass().getFields())
                if (!Modifier.isStatic(f.getModifiers()) && f.getType().isArray()
                    && f.get(raw) == null)
                    f.set(raw, Array.newInstance(f.getType().getComponentType(), 0));
            raw.getClass().getField("networkSupported").set(raw, false);
            if (Array.getLength(raw.getClass().getField("disks").get(raw)) != 0)
                throw new IllegalStateException("No disks allowed");
            kernel = raw.getClass().getField("kernel").get(raw);
            initrd = raw.getClass().getField("initrd").get(raw);
            Class<?> fd = type("android.os.ParcelFileDescriptor");
            console = fd.getMethod("open", File.class, int.class)
                          .invoke(null, new File(base, "console.txt"), 0x38000000);
            Class<?> vc = type("android.system.virtualizationservice.VirtualMachineConfig");
            Object wrapped = vc.getMethod("rawConfig", raw.getClass()).invoke(null, raw);
            vm = type("android.system.virtualizationservice.IVirtualizationService")
                     .getMethod("createVm", vc, fd, fd, fd, fd)
                     .invoke(service, wrapped, console, null, null, null);
            System.out.println("CREATED: GPU=gfxstream disks=0 memory=512MiB");
            iface.getMethod("start").invoke(vm);
            System.out.println("STARTED: cid=" + iface.getMethod("getCid").invoke(vm));
            int dead = type("android.system.virtualizationservice.VirtualMachineState")
                           .getField("DEAD")
                           .getInt(null);
            long deadline = System.nanoTime() + 30000000000L;
            while ((Integer) iface.getMethod("getState").invoke(vm) != dead
                && System.nanoTime() < deadline)
                Thread.sleep(100);
            String log = new String(
                java.nio.file.Files.readAllBytes(new File(base, "console.txt").toPath()), "UTF-8");
            passed = (Integer) iface.getMethod("getState").invoke(vm) == dead && verified(log);
            System.out.println("DROIDDECK_GPU_RESULT=" + (passed ? "passed" : "failed"));
            System.out.println(
                "This verifies virtio-gpu and Gfxstream context creation, not Vulkan rendering.");
        } catch (Throwable e) {
            if (e instanceof InvocationTargetException && e.getCause() != null)
                e = e.getCause();
            e.printStackTrace(System.out);
        } finally {
            if (vm != null)
                try {
                    int state = (Integer) iface.getMethod("getState").invoke(vm);
                    Class<?> states =
                        type("android.system.virtualizationservice.VirtualMachineState");
                    if (state != states.getField("DEAD").getInt(null)
                        && state != states.getField("NOT_STARTED").getInt(null))
                        iface.getMethod("stop").invoke(vm);
                } catch (Exception e) {
                    System.out.println("STOP: " + e);
                    passed = false;
                }
            for (Object f : new Object[] {kernel, initrd, console})
                if (f != null)
                    try {
                        f.getClass().getMethod("close").invoke(f);
                    } catch (Exception ignored) {
                    }
            watchdog.interrupt();
            System.out.println("PROBE DONE");
            if (owner != null)
                System.out.println("OWNER RELEASED");
        }
        System.exit(passed ? 0 : 1);
    }
}
