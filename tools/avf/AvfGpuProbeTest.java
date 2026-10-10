public final class AvfGpuProbeTest {
    static final String VALID = "DROIDDECK_VIRTGPU_BEGIN_V1\nDROIDDECK_VIRTGPU_NODE_V1\n"
        + "DROIDDECK_GFXSTREAM_CONTEXT_V1\nDROIDDECK_VIRTGPU_PASS_V1\n";

    private static void check(boolean condition, String name) {
        if (!condition)
            throw new AssertionError(name);
    }

    public static final class BoxedBuilder {
        boolean value;
        public void option(Boolean value) {
            this.value = value;
        }
    }
    public static final class PrimitiveBuilder {
        boolean value;
        public void option(boolean value) {
            this.value = value;
        }
    }

    public static void main(String[] args) throws Exception {
        check(AvfGpuProbe.verified(VALID), "complete guest report");
        check(AvfGpuProbe.verified(VALID.replace("\n", "\r\n")), "serial CRLF");
        check(!AvfGpuProbe.verified(VALID.replace("DROIDDECK_GFXSTREAM_CONTEXT_V1\n", "")),
            "missing GPU context");
        check(!AvfGpuProbe.verified(VALID + "DROIDDECK_VIRTGPU_PASS_V1\n"), "duplicate marker");
        check(!AvfGpuProbe.verified(VALID + "Kernel panic\n"), "panic after success");
        check(!AvfGpuProbe.verified(VALID + "DROIDDECK_VIRTGPU_CI_2D_V1\n"),
            "2D CI is not hardware Vulkan");
        check(!AvfGpuProbe.verified(VALID + "DROIDDECK_VIRTGPU_FAIL_V1\n"), "contradictory result");
        BoxedBuilder boxed = new BoxedBuilder();
        PrimitiveBuilder primitive = new PrimitiveBuilder();
        AvfGpuProbe.call(boxed, "option", boolean.class, true);
        AvfGpuProbe.call(primitive, "option", boolean.class, true);
        check(boxed.value && primitive.value, "Android API boolean variants");
        try {
            AvfGpuProbe.call(boxed, "missing", boolean.class, true);
            throw new AssertionError("missing API must fail");
        } catch (NoSuchMethodException expected) {
        }
        System.out.println("AVF GPU probe checks passed");
    }
}
