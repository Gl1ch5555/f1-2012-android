// Stub PPCImageConfig used only when generated/default/sources.cmake is absent
// (allows verifying the Android NDK + SDL3 + Vulkan + ReXGlue runtime build without default.xex).

#include "generated/default/f1_2012_init.h"

PPCFuncMapping PPCFuncMappings[] = {
    {0, nullptr},
};

const rex::PPCImageInfo PPCImageConfig = {
    .code_base = 0x82000000,
    .code_size = 0x01000000,
    .image_base = 0x82000000,
    .image_size = 0x02000000,
    .func_mappings = PPCFuncMappings,
    .rexcrt_heap = 0,
    .codegen_flags = {},
};
