## LWJGL-JavaFX Integration Demo

This repository contains an example of two-way LWJGL/JavaFX integration:

- LWJGL renders a 3D scene onto an offscreen framebuffer object and the result is displayed inside a JavaFX node.
- A JavaFX node is rendered to an Image and then uploaded to an OpenGL texture, which in turn is rendered within the LWJGL 3D scene.

The same idea could be applied to windowing systems other than JavaFX. This is what it currently looks like:

![Screenshot](http://cloud.github.com/downloads/Spasi/LWJGL-FX/lwjgl_javafx.png)