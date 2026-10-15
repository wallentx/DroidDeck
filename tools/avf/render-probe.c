/* A real offscreen Vulkan triangle, with deterministic pixel checks and PPM evidence.
 * Run in the guest with the Gfxstream ICD explicitly selected. No window is required. */
#define VK_NO_PROTOTYPES
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <vulkan/vulkan.h>

#include "triangle-frag.h"
#include "triangle-vert.h"

#define INSTANCE_FUNCTIONS(X)                                                          \
    X(DestroyInstance)                                                                 \
    X(EnumeratePhysicalDevices) X(GetPhysicalDeviceProperties)                         \
        X(GetPhysicalDeviceQueueFamilyProperties) X(GetPhysicalDeviceMemoryProperties) \
            X(CreateDevice) X(GetDeviceProcAddr)
#define DEVICE_FUNCTIONS(X)                                                                        \
    X(DestroyDevice)                                                                               \
    X(GetDeviceQueue) X(CreateImage) X(DestroyImage) X(GetImageMemoryRequirements)                 \
        X(AllocateMemory) X(FreeMemory) X(BindImageMemory) X(CreateBuffer) X(DestroyBuffer)        \
            X(GetBufferMemoryRequirements) X(BindBufferMemory) X(MapMemory) X(UnmapMemory)         \
                X(InvalidateMappedMemoryRanges) X(CreateImageView) X(DestroyImageView)             \
                    X(CreateRenderPass) X(DestroyRenderPass) X(CreateFramebuffer)                  \
                        X(DestroyFramebuffer) X(CreateShaderModule) X(DestroyShaderModule)         \
                            X(CreatePipelineLayout) X(DestroyPipelineLayout)                       \
                                X(CreateGraphicsPipelines) X(DestroyPipeline) X(CreateCommandPool) \
                                    X(DestroyCommandPool) X(AllocateCommandBuffers)                \
                                        X(BeginCommandBuffer) X(EndCommandBuffer)                  \
                                            X(CmdBeginRenderPass) X(CmdEndRenderPass)              \
                                                X(CmdBindPipeline) X(CmdDraw)                      \
                                                    X(CmdCopyImageToBuffer) X(CmdPipelineBarrier)  \
                                                        X(CreateFence) X(DestroyFence)             \
                                                            X(QueueSubmit) X(WaitForFences)
#define DECLARE(name) static PFN_vk##name vk##name;
INSTANCE_FUNCTIONS(DECLARE)
DEVICE_FUNCTIONS(DECLARE)
#undef DECLARE
#define CHECK(call)                                         \
    do {                                                    \
        VkResult r_ = (call);                               \
        if (r_ != VK_SUCCESS) {                             \
            fprintf(stderr, "%s returned %d\n", #call, r_); \
            goto cleanup;                                   \
        }                                                   \
    } while (0)
#define REQUIRE(condition, message)           \
    do {                                      \
        if (!(condition)) {                   \
            fprintf(stderr, "%s\n", message); \
            goto cleanup;                     \
        }                                     \
    } while (0)

static uint32_t memory_type(const VkPhysicalDeviceMemoryProperties* memory, uint32_t bits,
                            VkMemoryPropertyFlags required) {
    for (uint32_t i = 0; i < memory->memoryTypeCount && i < 32; i++)
        if ((bits & (1u << i)) && (memory->memoryTypes[i].propertyFlags & required) == required)
            return i;
    return UINT32_MAX;
}

int main(int argc, char** argv) {
    setvbuf(stdout, NULL, _IONBF, 0);
    if (argc != 3) {
        fprintf(stderr, "Usage: render-probe EXPECTED_VENDOR_HEX OUTPUT.ppm\n");
        return 2;
    }
    char* end = NULL;
    unsigned long vendor = strtoul(argv[1], &end, 0);
    if (!argv[1][0] || *end || vendor == 0 || vendor > UINT32_MAX) return 2;
    int passed = 0, submitted = 0, completed = 0;
    void *mapped = NULL, *loader = NULL;
    VkInstance instance = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory image_memory = VK_NULL_HANDLE, buffer_memory = VK_NULL_HANDLE;
    VkBuffer buffer = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
    VkRenderPass pass = VK_NULL_HANDLE;
    VkFramebuffer framebuffer = VK_NULL_HANDLE;
    VkShaderModule vertex = VK_NULL_HANDLE, fragment = VK_NULL_HANDLE;
    VkPipelineLayout layout = VK_NULL_HANDLE;
    VkPipeline pipeline = VK_NULL_HANDLE;
    VkCommandPool pool = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    enum { SIDE = 64, BYTES = SIDE * SIDE * 4 };
    loader = dlopen("libvulkan.so.1", RTLD_NOW | RTLD_LOCAL);
    REQUIRE(loader, "Cannot load libvulkan.so.1");
    PFN_vkGetInstanceProcAddr gipa =
        (PFN_vkGetInstanceProcAddr)dlsym(loader, "vkGetInstanceProcAddr");
    REQUIRE(gipa, "Missing vkGetInstanceProcAddr");
    PFN_vkCreateInstance create_instance = (PFN_vkCreateInstance)gipa(NULL, "vkCreateInstance");
    REQUIRE(create_instance, "Missing vkCreateInstance");
    VkApplicationInfo app = {.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
                             .pApplicationName = "DroidDeck AVF render probe",
                             .apiVersion = VK_API_VERSION_1_1};
    VkInstanceCreateInfo ici = {.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
                                .pApplicationInfo = &app};
    CHECK(create_instance(&ici, NULL, &instance));
#define LOAD_INSTANCE(name)                              \
    vk##name = (PFN_vk##name)gipa(instance, "vk" #name); \
    REQUIRE(vk##name, "Missing vk" #name);
    INSTANCE_FUNCTIONS(LOAD_INSTANCE)
#undef LOAD_INSTANCE
    uint32_t count = 0;
    CHECK(vkEnumeratePhysicalDevices(instance, &count, NULL));
    REQUIRE(count > 0 && count <= 16, "Unexpected physical device count");
    VkPhysicalDevice devices[16], physical = VK_NULL_HANDLE;
    CHECK(vkEnumeratePhysicalDevices(instance, &count, devices));
    VkPhysicalDeviceProperties properties;
    for (uint32_t i = 0; i < count; i++) {
        vkGetPhysicalDeviceProperties(devices[i], &properties);
        if (properties.vendorID == vendor && properties.deviceType != VK_PHYSICAL_DEVICE_TYPE_CPU) {
            physical = devices[i];
            break;
        }
    }
    REQUIRE(physical, "No non-CPU device with the expected vendor ID");
    printf("GPU: %s; vendor=0x%x; device=0x%x\n", properties.deviceName, properties.vendorID,
           properties.deviceID);
    uint32_t queues = 0, family = UINT32_MAX;
    vkGetPhysicalDeviceQueueFamilyProperties(physical, &queues, NULL);
    REQUIRE(queues > 0 && queues <= 32, "Unexpected queue family count");
    VkQueueFamilyProperties families[32];
    vkGetPhysicalDeviceQueueFamilyProperties(physical, &queues, families);
    for (uint32_t i = 0; i < queues; i++)
        if (families[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) {
            family = i;
            break;
        }
    REQUIRE(family != UINT32_MAX, "No graphics queue");
    float priority = 1;
    VkDeviceQueueCreateInfo qci = {.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
                                   .queueFamilyIndex = family,
                                   .queueCount = 1,
                                   .pQueuePriorities = &priority};
    VkDeviceCreateInfo dci = {.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
                              .queueCreateInfoCount = 1,
                              .pQueueCreateInfos = &qci};
    CHECK(vkCreateDevice(physical, &dci, NULL, &device));
#define LOAD_DEVICE(name)                                             \
    vk##name = (PFN_vk##name)vkGetDeviceProcAddr(device, "vk" #name); \
    REQUIRE(vk##name, "Missing vk" #name);
    DEVICE_FUNCTIONS(LOAD_DEVICE)
#undef LOAD_DEVICE
    VkQueue queue;
    vkGetDeviceQueue(device, family, 0, &queue);
    VkPhysicalDeviceMemoryProperties memory;
    vkGetPhysicalDeviceMemoryProperties(physical, &memory);
    VkImageCreateInfo image_info = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
        .imageType = VK_IMAGE_TYPE_2D,
        .format = VK_FORMAT_R8G8B8A8_UNORM,
        .extent = {SIDE, SIDE, 1},
        .mipLevels = 1,
        .arrayLayers = 1,
        .samples = VK_SAMPLE_COUNT_1_BIT,
        .tiling = VK_IMAGE_TILING_OPTIMAL,
        .usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT};
    CHECK(vkCreateImage(device, &image_info, NULL, &image));
    VkMemoryRequirements requirements;
    vkGetImageMemoryRequirements(device, image, &requirements);
    uint32_t type =
        memory_type(&memory, requirements.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (type == UINT32_MAX) type = memory_type(&memory, requirements.memoryTypeBits, 0);
    REQUIRE(type != UINT32_MAX, "No image memory type");
    VkMemoryAllocateInfo allocation = {.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
                                       .allocationSize = requirements.size,
                                       .memoryTypeIndex = type};
    CHECK(vkAllocateMemory(device, &allocation, NULL, &image_memory));
    CHECK(vkBindImageMemory(device, image, image_memory, 0));
    VkBufferCreateInfo buffer_info = {.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
                                      .size = BYTES,
                                      .usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT};
    CHECK(vkCreateBuffer(device, &buffer_info, NULL, &buffer));
    vkGetBufferMemoryRequirements(device, buffer, &requirements);
    type = memory_type(&memory, requirements.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT);
    REQUIRE(type != UINT32_MAX, "No host-readable memory type");
    allocation.allocationSize = requirements.size;
    allocation.memoryTypeIndex = type;
    CHECK(vkAllocateMemory(device, &allocation, NULL, &buffer_memory));
    CHECK(vkBindBufferMemory(device, buffer, buffer_memory, 0));
    VkImageViewCreateInfo view_info = {.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
                                       .image = image,
                                       .viewType = VK_IMAGE_VIEW_TYPE_2D,
                                       .format = VK_FORMAT_R8G8B8A8_UNORM,
                                       .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
    CHECK(vkCreateImageView(device, &view_info, NULL, &view));
    VkAttachmentDescription attachment = {.format = VK_FORMAT_R8G8B8A8_UNORM,
                                          .samples = VK_SAMPLE_COUNT_1_BIT,
                                          .loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR,
                                          .storeOp = VK_ATTACHMENT_STORE_OP_STORE,
                                          .stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE,
                                          .stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE,
                                          .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
                                          .finalLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL};
    VkAttachmentReference reference = {0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkSubpassDescription subpass = {.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS,
                                    .colorAttachmentCount = 1,
                                    .pColorAttachments = &reference};
    VkSubpassDependency dependencies[2] = {
        {.srcSubpass = VK_SUBPASS_EXTERNAL,
         .dstSubpass = 0,
         .srcStageMask = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
         .dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
         .dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT},
        {.srcSubpass = 0,
         .dstSubpass = VK_SUBPASS_EXTERNAL,
         .srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
         .dstStageMask = VK_PIPELINE_STAGE_TRANSFER_BIT,
         .srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
         .dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT}};
    VkRenderPassCreateInfo pass_info = {.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO,
                                        .attachmentCount = 1,
                                        .pAttachments = &attachment,
                                        .subpassCount = 1,
                                        .pSubpasses = &subpass,
                                        .dependencyCount = 2,
                                        .pDependencies = dependencies};
    CHECK(vkCreateRenderPass(device, &pass_info, NULL, &pass));
    VkFramebufferCreateInfo fb_info = {.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO,
                                       .renderPass = pass,
                                       .attachmentCount = 1,
                                       .pAttachments = &view,
                                       .width = SIDE,
                                       .height = SIDE,
                                       .layers = 1};
    CHECK(vkCreateFramebuffer(device, &fb_info, NULL, &framebuffer));
    VkShaderModuleCreateInfo shader = {.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
                                       .codeSize = sizeof(triangle_vert),
                                       .pCode = triangle_vert};
    CHECK(vkCreateShaderModule(device, &shader, NULL, &vertex));
    shader.codeSize = sizeof(triangle_frag);
    shader.pCode = triangle_frag;
    CHECK(vkCreateShaderModule(device, &shader, NULL, &fragment));
    VkPipelineShaderStageCreateInfo stages[2] = {
        {.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
         .stage = VK_SHADER_STAGE_VERTEX_BIT,
         .module = vertex,
         .pName = "main"},
        {.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
         .stage = VK_SHADER_STAGE_FRAGMENT_BIT,
         .module = fragment,
         .pName = "main"}};
    VkPipelineVertexInputStateCreateInfo input = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
    VkPipelineInputAssemblyStateCreateInfo assembly = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO,
        .topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST};
    VkViewport viewport = {0, 0, SIDE, SIDE, 0, 1};
    VkRect2D scissor = {{0, 0}, {SIDE, SIDE}};
    VkPipelineViewportStateCreateInfo viewport_info = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO,
        .viewportCount = 1,
        .pViewports = &viewport,
        .scissorCount = 1,
        .pScissors = &scissor};
    VkPipelineRasterizationStateCreateInfo raster = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO,
        .polygonMode = VK_POLYGON_MODE_FILL,
        .cullMode = VK_CULL_MODE_NONE,
        .frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE,
        .lineWidth = 1};
    VkPipelineMultisampleStateCreateInfo samples = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO,
        .rasterizationSamples = VK_SAMPLE_COUNT_1_BIT};
    VkPipelineColorBlendAttachmentState blend_attachment = {.colorWriteMask = 15};
    VkPipelineColorBlendStateCreateInfo blend = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO,
        .attachmentCount = 1,
        .pAttachments = &blend_attachment};
    VkPipelineLayoutCreateInfo layout_info = {.sType =
                                                  VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    CHECK(vkCreatePipelineLayout(device, &layout_info, NULL, &layout));
    VkGraphicsPipelineCreateInfo pipeline_info = {
        .sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO,
        .stageCount = 2,
        .pStages = stages,
        .pVertexInputState = &input,
        .pInputAssemblyState = &assembly,
        .pViewportState = &viewport_info,
        .pRasterizationState = &raster,
        .pMultisampleState = &samples,
        .pColorBlendState = &blend,
        .layout = layout,
        .renderPass = pass,
        .basePipelineIndex = -1};
    CHECK(vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, 1, &pipeline_info, NULL, &pipeline));
    puts("PIPELINE_READY");
    VkCommandPoolCreateInfo pool_info = {.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
                                         .queueFamilyIndex = family};
    CHECK(vkCreateCommandPool(device, &pool_info, NULL, &pool));
    VkCommandBufferAllocateInfo command_info = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
        .commandPool = pool,
        .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
        .commandBufferCount = 1};
    VkCommandBuffer command;
    CHECK(vkAllocateCommandBuffers(device, &command_info, &command));
    VkCommandBufferBeginInfo begin = {.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
                                      .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT};
    CHECK(vkBeginCommandBuffer(command, &begin));
    VkClearValue clear = {.color = {{0, 0, 1, 1}}};
    VkRenderPassBeginInfo render = {.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO,
                                    .renderPass = pass,
                                    .framebuffer = framebuffer,
                                    .renderArea = scissor,
                                    .clearValueCount = 1,
                                    .pClearValues = &clear};
    vkCmdBeginRenderPass(command, &render, VK_SUBPASS_CONTENTS_INLINE);
    vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
    vkCmdDraw(command, 3, 1, 0, 0);
    vkCmdEndRenderPass(command);
    VkBufferImageCopy copy = {.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
                              .imageExtent = {SIDE, SIDE, 1}};
    vkCmdCopyImageToBuffer(command, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, buffer, 1, &copy);
    VkBufferMemoryBarrier barrier = {.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER,
                                     .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
                                     .dstAccessMask = VK_ACCESS_HOST_READ_BIT,
                                     .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                                     .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                                     .buffer = buffer,
                                     .size = VK_WHOLE_SIZE};
    vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 0,
                         NULL, 1, &barrier, 0, NULL);
    CHECK(vkEndCommandBuffer(command));
    VkFenceCreateInfo fence_info = {.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    CHECK(vkCreateFence(device, &fence_info, NULL, &fence));
    VkSubmitInfo submit = {.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
                           .commandBufferCount = 1,
                           .pCommandBuffers = &command};
    CHECK(vkQueueSubmit(queue, 1, &submit, fence));
    submitted = 1;
    puts("SUBMITTED");
    CHECK(vkWaitForFences(device, 1, &fence, VK_TRUE, 10000000000ULL));
    completed = 1;
    puts("FENCE_SIGNALED");
    CHECK(vkMapMemory(device, buffer_memory, 0, VK_WHOLE_SIZE, 0, &mapped));
    VkMappedMemoryRange range = {.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE,
                                 .memory = buffer_memory,
                                 .size = VK_WHOLE_SIZE};
    CHECK(vkInvalidateMappedMemoryRanges(device, 1, &range));
    const uint8_t* pixels = mapped;
    unsigned red = 0, blue = 0, other = 0;
    for (unsigned i = 0; i < SIDE * SIDE; i++) {
        const uint8_t* p = pixels + i * 4;
        if (p[0] == 255 && p[1] == 0 && p[2] == 0 && p[3] == 255)
            red++;
        else if (p[0] == 0 && p[1] == 0 && p[2] == 255 && p[3] == 255)
            blue++;
        else
            other++;
    }
    const uint8_t* center = pixels + (SIDE / 2 * SIDE + SIDE / 2) * 4;
    int valid_pixels =
        red > 1000 && red < 1400 && other == 0 && center[0] == 255 && pixels[2] == 255;
    printf("Readback: red=%u blue=%u other=%u\n", red, blue, other);
    FILE* output = fopen(argv[2], "wb");
    REQUIRE(output, "Cannot write output image");
    int written = fprintf(output, "P6\n%d %d\n255\n", SIDE, SIDE) > 0;
    for (unsigned i = 0; i < SIDE * SIDE && written; i++)
        written = fwrite(pixels + i * 4, 1, 3, output) == 3;
    if (fclose(output)) written = 0;
    REQUIRE(written, "Output image write failed");
    REQUIRE(valid_pixels, "Triangle/background pixel validation failed");
    printf(
        "{\"status\":\"passed\",\"vendor_id\":%u,\"red_pixels\":%u,\"blue_pixels\":%u,\"other_"
        "pixels\":%u}\n",
        properties.vendorID, red, blue, other);
    passed = 1;
cleanup:
    /* A timed-out submission must not turn cleanup into an unbounded DeviceWaitIdle. */
    if (!submitted || completed) {
        if (mapped) vkUnmapMemory(device, buffer_memory);
        if (fence) vkDestroyFence(device, fence, NULL);
        if (pool) vkDestroyCommandPool(device, pool, NULL);
        if (pipeline) vkDestroyPipeline(device, pipeline, NULL);
        if (layout) vkDestroyPipelineLayout(device, layout, NULL);
        if (vertex) vkDestroyShaderModule(device, vertex, NULL);
        if (fragment) vkDestroyShaderModule(device, fragment, NULL);
        if (framebuffer) vkDestroyFramebuffer(device, framebuffer, NULL);
        if (view) vkDestroyImageView(device, view, NULL);
        if (pass) vkDestroyRenderPass(device, pass, NULL);
        if (buffer) vkDestroyBuffer(device, buffer, NULL);
        if (buffer_memory) vkFreeMemory(device, buffer_memory, NULL);
        if (image) vkDestroyImage(device, image, NULL);
        if (image_memory) vkFreeMemory(device, image_memory, NULL);
        if (device && vkDestroyDevice) vkDestroyDevice(device, NULL);
        if (instance && vkDestroyInstance) vkDestroyInstance(instance, NULL);
        if (loader) dlclose(loader);
    }
    return passed ? 0 : 1;
}
