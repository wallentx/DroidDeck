import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

public final class StageGraphicsKernelTest {
    private static void check(boolean value, String message) {
        if (!value)
            throw new AssertionError(message);
    }

    private static void write(Path path, byte[] data) throws Exception {
        Files.write(path, data);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("hold")) {
            try (FileChannel channel =
                     FileChannel.open(Paths.get(args[1]), StandardOpenOption.WRITE);
                FileLock ignored = channel.lock()) {
                System.out.println("LOCKED");
                System.out.flush();
                System.in.read();
            }
            return;
        }
        Path base = Files.createTempDirectory("avf-stage-test-");
        Path source = Files.createDirectory(base.resolve("candidate"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        int uid = ((Number) Files.getAttribute(base, "unix:uid")).intValue();
        byte[] original = "normal kernel untouched".getBytes("UTF-8");
        byte[] disk = "disk sentinel".getBytes("UTF-8");
        write(base.resolve("Image"), original);
        write(base.resolve("arch-rootfs.img"), disk);
        Path owner = base.resolve("owner.lock");
        write(owner, new byte[0]);
        Object inode = Files.getAttribute(owner, "unix:ino");
        byte[] image = new byte[4096];
        image[56] = 'A';
        image[57] = 'R';
        image[58] = 'M';
        image[59] = 'd';
        Path candidate = source.resolve("Image");
        write(candidate, image);
        String hash = KernelUpgrade.digest(candidate);
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        Process holder = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
            "StageGraphicsKernelTest", "hold", owner.toString())
                             .start();
        try {
            check(
                "LOCKED".equals(
                    new BufferedReader(new InputStreamReader(holder.getInputStream())).readLine()),
                "holder failed");
            try {
                StageGraphicsKernel.stage(base, candidate, hash, uid);
                throw new AssertionError("active owner accepted");
            } catch (IOException expected) {
            }
            check(!Files.exists(base.resolve("Image-gfxstream")), "busy call published a kernel");
        } finally {
            holder.getOutputStream().write(1);
            holder.getOutputStream().close();
            if (!holder.waitFor(5, TimeUnit.SECONDS)) {
                holder.destroyForcibly();
                throw new AssertionError("holder did not exit");
            }
        }
        try {
            StageGraphicsKernel.stage(base, candidate,
                "0000000000000000000000000000000000000000000000000000000000000000", uid);
            throw new AssertionError("wrong checksum accepted");
        } catch (IOException expected) {
        }
        check(!Files.exists(base.resolve("Image-gfxstream")), "bad checksum published a kernel");
        check("staged".equals(StageGraphicsKernel.stage(base, candidate, hash, uid)),
            "initial stage");
        check("unchanged".equals(StageGraphicsKernel.stage(base, candidate, hash, uid)),
            "idempotence");
        image[0] = 1;
        write(candidate, image);
        try {
            StageGraphicsKernel.stage(base, candidate, KernelUpgrade.digest(candidate), uid);
            throw new AssertionError("existing kernel replaced");
        } catch (IOException expected) {
        }
        check(hash.equals(KernelUpgrade.digest(base.resolve("Image-gfxstream"))),
            "staged kernel changed");
        check(Arrays.equals(original, Files.readAllBytes(base.resolve("Image"))),
            "normal kernel changed");
        check(Arrays.equals(disk, Files.readAllBytes(base.resolve("arch-rootfs.img"))),
            "disk changed");
        check(inode.equals(Files.getAttribute(owner, "unix:ino")), "lock inode changed");
        // Remove only this test's private fixtures.
        Files.delete(candidate);
        Files.delete(source);
        Files.delete(base.resolve("Image-gfxstream"));
        Files.delete(base.resolve("Image"));
        Files.delete(base.resolve("arch-rootfs.img"));
        Files.delete(owner);
        Files.delete(base);
        System.out.println("Graphics kernel staging checks passed");
    }
}
