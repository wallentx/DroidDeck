import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.UUID;

/** Stage an additional kernel slot using Termux-Aether's validated maintenance primitives. */
public final class StageGraphicsKernel {
    static String stage(Path base, Path source, String expected, int uid) throws Exception {
        KernelUpgrade.validate(base, true, uid);
        KernelUpgrade.validate(source.getParent(), true, uid);
        Path owner = base.resolve("owner.lock");
        KernelUpgrade.validate(owner, false, uid);
        // Open without CREATE, and use the same POSIX lock as ArchVmUserService.
        try (FileChannel channel =
                 FileChannel.open(owner, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            FileLock lock = channel.tryLock()) {
            if (lock == null)
                throw new IOException("Arch is owned; close its sessions first");
            KernelUpgrade.image(source, expected, uid);
            // Confirm this is an existing installation; do not open either file.
            KernelUpgrade.validate(base.resolve("Image"), false, uid);
            KernelUpgrade.validate(base.resolve("arch-rootfs.img"), false, uid);
            Path destination = base.resolve("Image-gfxstream");
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                // Idempotent only for the exact same verified kernel. Never replace one.
                KernelUpgrade.image(destination, expected, uid);
                KernelUpgrade.syncDirectory(base);
                return "unchanged";
            }
            Path pending = base.resolve("Image-gfxstream.pending-" + UUID.randomUUID());
            try {
                KernelUpgrade.copyDurable(source, pending);
                KernelUpgrade.image(pending, expected, uid);
                Files.move(pending, destination, StandardCopyOption.ATOMIC_MOVE);
                KernelUpgrade.syncDirectory(base);
                KernelUpgrade.image(destination, expected, uid);
                return "staged";
            } finally {
                Files.deleteIfExists(pending);
            }
        }
    }

    public static void main(String[] args) {
        try {
            if (args.length != 2 || !args[0].matches("/data/local/tmp/droiddeck-gpu-[0-9a-f]{32}"))
                throw new IllegalArgumentException("PROBE_STAGE EXPECTED_IMAGE_SHA256 required");
            int uid = (Integer) Class.forName("android.os.Process").getMethod("myUid").invoke(null);
            if (uid != 2000)
                throw new SecurityException("Shell Shizuku required");
            String result = stage(KernelUpgrade.BASE, Paths.get(args[0], "Image"), args[1], uid);
            System.out.println("{\"operation\":\"" + result + "\",\"image_sha256\":\"" + args[1]
                + "\",\"normal_kernel_replaced\":false,\"guest_disk_opened\":false}");
        } catch (Exception error) {
            error.printStackTrace(System.err);
            System.exit(1);
        }
    }
}
