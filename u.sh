cd gama
curl -L https://github.com/hqnghi88/agama/releases/download/draft-20260308070312/gama.application-linux.gtk.aarch64.tar.gz | tar -xz
# Display routing for Termux-X11
export DISPLAY=:0

# Enable Zink (OpenGL over Vulkan)
export MESA_LOADER_DRIVER_OVERRIDE=zink
export GALLIUM_DRIVER=zink

# Optimize Zink for Adreno
export ZINK_DESCRIPTORS=lazy

# Bypass strict conformance checks for better performance on mobile GPUs
export TU_DEBUG=noconform
