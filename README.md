## LWJGL-JavaFX Integration Demo

This repository contains an example of two-way LWJGL/JavaFX integration:

- LWJGL renders a 3D scene onto an offscreen framebuffer object and the result is displayed inside a JavaFX node.
- A JavaFX node is rendered to an Image and then uploaded to an OpenGL texture, which in turn is rendered within the LWJGL 3D scene.

The same idea could be applied to windowing systems other than JavaFX. This is what it currently looks like:

![Screenshot](http://cloud.github.com/downloads/Spasi/LWJGL-FX/lwjgl_javafx.jpg)

## How to Build

This demo requires JDK 7 or higher and [Apache Ant](http://ant.apache.org/) to build and run. Also:

- Download an LWJGL distribution and extract it in the lib folder. Preferably the latest [nightly build](http://ci.newdawnsoftware.com/job/LWJGL-git-dist/lastBuild/).
- Open build.xml and set the first two properties, _JDK_ and _LWJGL\_PATH_ to the appropriate values.

Then type _ant_ or _ant run_.

## Implementation Notes

- **IMPORTANT**: This is a proof-of-concept demo and is not meant to be used in production. The performance overhead is horrible and burns tons of unnecessary
bandwidth/power. I wrote this demo just to showcase what would be possible if JavaFX was open to native OpenGL integration. An efficient integration would
require JavaFX to support (at least) GPU-to-GPU texture/framebuffer copies (via the OpenGL pipeline or even with WGL\_NV\_DX\_interop on Windows). There are
currently no (known) plans for this to happen.

- The OpenGL rendering currently synchronizes to 60Hz independently of the JavaFX rendering loop. It would be more efficient, and probably result in smoother
animation, if the two were in sync.

- There are more efficient ways to implement the data transfers from/to the GPU, by taking advantage of the independent copy engines present on modern GPUs.
Such techniques require more threads and OpenGL contexts, but this demo is already complex enough. More details can be found [here](http://on-demand.gputechconf.com/gtc/2012/presentations/S0356-GTC2012-Texture-Transfers.pdf).