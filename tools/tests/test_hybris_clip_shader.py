"""Compile and exercise the pinned libhybris MaliBridge distance lowering."""

import ctypes
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import unittest

if __package__:
    from .test_hybris_vendor_compat import vulkan_include
else:
    from test_hybris_vendor_compat import vulkan_include

ROOT = Path(__file__).resolve().parents[2]
WORD = ctypes.c_uint32
WORD_POINTER = ctypes.POINTER(WORD)


class DistanceCode(ctypes.Structure):
    _fields_ = [("code", WORD_POINTER), ("size", ctypes.c_size_t)]


class DistanceStage(ctypes.Structure):
    _fields_ = [("stage", ctypes.c_uint32), ("entry", ctypes.c_char_p),
                ("code", WORD_POINTER), ("size", ctypes.c_size_t)]


def instructions(data):
    words = struct.unpack(f"<{len(data) // 4}I", data)
    cursor = 5
    while cursor < len(words):
        count = words[cursor] >> 16
        assert count and cursor + count <= len(words)
        yield words[cursor] & 0xffff, words[cursor + 1:cursor + count]
        cursor += count


def shader(stage):
    vertex = stage == "vertex"
    return "\n".join([
        "OpCapability Shader", "OpCapability ClipDistance" if vertex else "",
        "OpMemoryModel Logical GLSL450",
        ('OpEntryPoint Vertex %main "main" %clip %position' if vertex else
         'OpEntryPoint Fragment %main "main" %color'),
        "" if vertex else "OpExecutionMode %main OriginUpperLeft",
        "OpSource Unknown 0", 'OpSourceContinued "distance regression fixture"',
        'OpName %main "main"', 'OpModuleProcessed "MaliBridge distance regression"',
        ("OpDecorate %clip BuiltIn ClipDistance\nOpDecorate %position BuiltIn Position"
         if vertex else "OpDecorate %color Location 0"),
        "%void = OpTypeVoid", "%function = OpTypeFunction %void",
        "%float = OpTypeFloat 32", "%uint = OpTypeInt 32 0",
        "%zero_index = OpConstant %uint 0", "%one_index = OpConstant %uint 1",
        "%two = OpConstant %uint 2", "%one = OpConstant %float 1",
        "%vector = OpTypeVector %float 4",
        "%rgba = OpConstantComposite %vector %one %one %one %one",
        "%vector_pointer = OpTypePointer Output %vector",
        ("%array = OpTypeArray %float %two\n%array_pointer = OpTypePointer Output %array\n"
         "%float_pointer = OpTypePointer Output %float\n%clip = OpVariable %array_pointer Output\n"
         "%position = OpVariable %vector_pointer Output" if vertex else
         "%color = OpVariable %vector_pointer Output"),
        "%main = OpFunction %void None %function", "%entry = OpLabel",
        ("%first = OpAccessChain %float_pointer %clip %zero_index\nOpStore %first %one\n"
         "%second = OpAccessChain %float_pointer %clip %one_index\nOpStore %second %one\n"
         "OpStore %position %rgba" if vertex else "OpStore %color %rgba"),
        "OpReturn", "OpFunctionEnd", "",
    ])


def per_vertex_block_shader(model):
    stage = "Geometry" if model == 3 else "TessellationEvaluation"
    capability = "Geometry" if model == 3 else "Tessellation"
    modes = (["OpExecutionMode %main InputPoints", "OpExecutionMode %main OutputPoints",
              "OpExecutionMode %main OutputVertices 1"] if model == 3 else
             ["OpExecutionMode %main Triangles", "OpExecutionMode %main SpacingEqual",
              "OpExecutionMode %main VertexOrderCcw"])
    body = ["%position_ptr = OpAccessChain %vector_pointer %out %zero_index",
            "OpStore %position_ptr %rgba",
            "%clip_ptr = OpAccessChain %float_pointer %out %one_index %zero_index",
            "OpStore %clip_ptr %one"]
    if model == 3:
        body += ["OpEmitVertex", "OpEndPrimitive"]
    return "\n".join([
        "OpCapability Shader", f"OpCapability {capability}", "OpCapability ClipDistance",
        "OpMemoryModel Logical GLSL450", f'OpEntryPoint {stage} %main "main" %out', *modes,
        "OpMemberDecorate %per_vertex 0 BuiltIn Position",
        "OpMemberDecorate %per_vertex 1 BuiltIn ClipDistance", "OpDecorate %per_vertex Block",
        "%void = OpTypeVoid", "%function = OpTypeFunction %void", "%float = OpTypeFloat 32",
        "%uint = OpTypeInt 32 0", "%zero_index = OpConstant %uint 0",
        "%one_index = OpConstant %uint 1", "%two = OpConstant %uint 2",
        "%one = OpConstant %float 1", "%vector = OpTypeVector %float 4",
        "%rgba = OpConstantComposite %vector %one %one %one %one",
        "%clip_array = OpTypeArray %float %two", "%per_vertex = OpTypeStruct %vector %clip_array",
        "%block_pointer = OpTypePointer Output %per_vertex", "%vector_pointer = OpTypePointer Output %vector",
        "%float_pointer = OpTypePointer Output %float", "%out = OpVariable %block_pointer Output",
        "%main = OpFunction %void None %function", "%entry = OpLabel", *body,
        "OpReturn", "OpFunctionEnd", "",
    ])


class HybrisClipShaderTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        source_value = os.environ.get("HYBRIS_CLIP_SOURCE")
        if not source_value:
            raise unittest.SkipTest("set HYBRIS_CLIP_SOURCE to the pinned libhybris checkout")
        cls.compiler = shutil.which(os.environ.get("CC", "cc"))
        cls.assembler = shutil.which("spirv-as")
        cls.validator = shutil.which("spirv-val")
        if not all((cls.compiler, cls.assembler, cls.validator)):
            raise RuntimeError("HYBRIS_CLIP_SOURCE requires a C compiler and spirv-tools")
        pristine = Path(source_value)
        if not (pristine / "hybris/vulkan/compat/malibridge/spirv_clip.c").is_file():
            raise RuntimeError("HYBRIS_CLIP_SOURCE must point to the pinned libhybris checkout")
        cls.temporary = tempfile.TemporaryDirectory(prefix="droiddeck-distance-shader-")
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.directory = Path(cls.temporary.name)
        checkout = cls.directory / "libhybris"
        subprocess.run(["git", "clone", "-q", "--no-hardlinks", str(pristine), str(checkout)], check=True)
        for patch in sorted((ROOT / "tools/hybris/patches").glob("*.patch")):
            subprocess.run(["git", "-C", str(checkout), "apply", "--check", str(patch)], check=True)
            subprocess.run(["git", "-C", str(checkout), "apply", str(patch)], check=True)
        source = checkout / "hybris/vulkan/compat"
        allocator = cls.directory / "allocator.c"
        allocator.write_text(
            "#include <vulkan/vulkan.h>\n#include <stdlib.h>\n"
            "void *hybris_scaled_alloc(const VkAllocationCallbacks *a, size_t n, "
            "VkSystemAllocationScope s) { (void)a; (void)s; return malloc(n); }\n"
            "void hybris_scaled_free(const VkAllocationCallbacks *a, void *p) "
            "{ (void)a; free(p); }\n")
        library = cls.directory / "distance.so"
        built = subprocess.run([
            cls.compiler, "-shared", "-fPIC", "-std=gnu11", "-Wall", "-Wextra", "-Werror",
            "-I", str(vulkan_include()), "-I", str(source),
            str(source / "distance_lowering.c"), str(source / "spirv_entry.c"),
            str(source / "spirv_decorations.c"), str(source / "spirv_builtins.c"),
            *(str(source / "malibridge" / name) for name in
              ("spirv_edit.c", "spirv_clip.c", "spirv_pervertex.c")),
            str(allocator), "-o", str(library)], capture_output=True, text=True)
        if built.returncode:
            raise RuntimeError(built.stdout + built.stderr)
        cls.library = ctypes.CDLL(str(library))
        cls.library.hybris_distance_module.argtypes = [
            ctypes.c_uint, ctypes.c_int, WORD_POINTER, ctypes.c_size_t, ctypes.c_void_p,
            ctypes.POINTER(DistanceCode), ctypes.POINTER(ctypes.c_char_p)]
        cls.library.hybris_distance_pipeline.argtypes = [
            ctypes.c_uint, ctypes.c_int, ctypes.c_uint, ctypes.POINTER(DistanceStage),
            ctypes.c_void_p, ctypes.POINTER(DistanceCode), ctypes.POINTER(ctypes.c_char_p)]
        cls.library.hybris_scaled_free.argtypes = [ctypes.c_void_p, ctypes.c_void_p]

    def assemble(self, stage):
        text = self.directory / f"{stage}.spvasm"
        binary = self.directory / f"{stage}.spv"
        text.write_text(shader(stage))
        subprocess.run([self.assembler, "--target-env", "vulkan1.1", str(text), "-o", str(binary)],
                       check=True, capture_output=True, text=True)
        data = binary.read_bytes()
        self.validate(data)
        return data

    def assemble_text(self, name, source):
        text = self.directory / f"{name}.spvasm"
        binary = self.directory / f"{name}.spv"
        text.write_text(source)
        subprocess.run([self.assembler, "--target-env", "vulkan1.1", str(text), "-o", str(binary)],
                       check=True, capture_output=True, text=True)
        data = binary.read_bytes()
        self.validate(data)
        return data

    def validate(self, data):
        binary = self.directory / "validate.spv"
        binary.write_bytes(data)
        result = subprocess.run([self.validator, "--target-env", "vulkan1.1", str(binary)],
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def convert_module(self, data):
        code = (WORD * (len(data) // 4)).from_buffer_copy(data)
        out, reason = DistanceCode(), ctypes.c_char_p()
        result = self.library.hybris_distance_module(
            1, 0, code, len(data), None, ctypes.byref(out), ctypes.byref(reason))
        self.assertEqual(result, 0, reason.value.decode() if reason.value else "")
        try:
            self.assertTrue(out.code and out.size)
            return ctypes.string_at(out.code, out.size)
        finally:
            self.library.hybris_scaled_free(None, out.code)

    def test_module_demotes_clip_and_preserves_logical_layout(self):
        original = self.assemble("vertex")
        stream = list(instructions(original))
        main = next(operands[1] for op, operands in stream if op == 15)
        float_type = next(operands[0] for op, operands in stream if op == 22)
        zero = next(operands[1] for op, operands in stream if op == 43 and operands[2] == 0)
        mode = (main, 6028, float_type, zero)
        position = max(i for i, (op, _) in enumerate(stream) if op in (15, 16)) + 1
        stream.insert(position, (331, mode))
        words = list(struct.unpack("<5I", original[:20]))
        for opcode, operands in stream:
            words.extend([((len(operands) + 1) << 16) | opcode, *operands])
        converted = self.convert_module(struct.pack(f"<{len(words)}I", *words))
        emitted = list(instructions(converted))
        self.assertFalse(any(op == 17 and operands == (32,) for op, operands in emitted))
        self.assertFalse(any(op == 71 and operands[1:] == (11, 3) for op, operands in emitted))
        self.assertTrue(any(op == 71 and operands[1] == 30 for op, operands in emitted))
        annotation = next(i for i, (op, _) in enumerate(emitted) if op in (71, 72))
        for opcode in (2, 330, 331):
            self.assertLess(next(i for i, (op, _) in enumerate(emitted) if op == opcode), annotation)

    def test_pipeline_adds_fragment_discard(self):
        data = [self.assemble("vertex"), self.assemble("fragment")]
        words = [(WORD * (len(item) // 4)).from_buffer_copy(item) for item in data]
        stages = (DistanceStage * 2)(
            DistanceStage(1, b"main", words[0], len(data[0])),
            DistanceStage(16, b"main", words[1], len(data[1])))
        outputs, reason = (DistanceCode * 2)(), ctypes.c_char_p()
        result = self.library.hybris_distance_pipeline(
            1, 0, 2, stages, None, outputs, ctypes.byref(reason))
        self.assertEqual(result, 0, reason.value.decode() if reason.value else "")
        try:
            converted = [ctypes.string_at(item.code, item.size) for item in outputs]
            for item in converted:
                self.validate(item)
            self.assertTrue(any(op in (252, 4416) for op, _ in instructions(converted[1])))
        finally:
            for item in outputs:
                self.library.hybris_scaled_free(None, item.code)

    def test_geometry_and_tessellation_blocks_are_demoted(self):
        for model in (3, 2):
            with self.subTest(model=model):
                converted = self.convert_module(
                    self.assemble_text(f"block-{model}", per_vertex_block_shader(model)))
                self.validate(converted)
                emitted = list(instructions(converted))
                self.assertFalse(any(op == 72 and operands[2:] == (11, 3)
                                     for op, operands in emitted))
                self.assertTrue(any(op == 71 and operands[1] == 30 for op, operands in emitted))


if __name__ == "__main__":
    unittest.main()
