#include "nested_swapchain.hpp"
#include <cassert>
#include <cstdio>

int main()
{
    VkSurfaceCapabilitiesKHR caps = {};
    caps.minImageCount = 2;
    caps.maxImageCount = 3;
    caps.currentExtent = {UINT32_MAX, UINT32_MAX};
    caps.minImageExtent = {16, 16};
    caps.maxImageExtent = {4096, 4096};
    caps.supportedCompositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    caps.supportedUsageFlags = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT |
        VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    gamescope::NestedSwapchainConfig config;

    // Existing desktop drivers retain direct composition into mutable swapchains.
    assert(gamescope::ChooseNestedSwapchainConfig(caps, true, 1280, 720, config));
    assert(!config.copy && config.imageCount == 3 && config.extent.width == 1280);

    // No mutable formats: compose offscreen and request only a copy destination.
    assert(gamescope::ChooseNestedSwapchainConfig(caps, false, 1280, 720, config));
    assert(config.copy && config.usage == VK_IMAGE_USAGE_TRANSFER_DST_BIT);

    // The Android bridge exposes transfer usage and inherited alpha, but no storage.
    caps.supportedUsageFlags &= ~VK_IMAGE_USAGE_STORAGE_BIT;
    caps.supportedCompositeAlpha = VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
    caps.maxImageCount = 2;
    caps.currentExtent = {1602, 720};
    assert(gamescope::ChooseNestedSwapchainConfig(caps, true, 1920, 1080, config));
    assert(config.copy && config.alpha == VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR);
    assert(config.imageCount == 2 && config.extent.width == 1602);

    // Fail rather than submitting unsupported image usage or alpha.
    caps.supportedUsageFlags &= ~VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    assert(!gamescope::ChooseNestedSwapchainConfig(caps, true, 1280, 720, config));
    caps.supportedUsageFlags |= VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    caps.supportedCompositeAlpha = 0;
    assert(!gamescope::ChooseNestedSwapchainConfig(caps, false, 1280, 720, config));
    caps.supportedCompositeAlpha = VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;

    caps.currentExtent = {0, 0};
    assert(!gamescope::ChooseNestedSwapchainConfig(caps, false, 1280, 720, config));
    caps.currentExtent = {UINT32_MAX, UINT32_MAX};
    assert(gamescope::ChooseNestedSwapchainConfig(caps, false, 1, 9000, config));
    assert(config.extent.width == 16 && config.extent.height == 4096);
    caps.maxImageCount = 1;
    assert(!gamescope::ChooseNestedSwapchainConfig(caps, false, 1280, 720, config));
    std::puts("nested swapchain capability tests passed");
}
