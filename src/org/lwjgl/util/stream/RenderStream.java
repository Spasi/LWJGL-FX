package org.lwjgl.util.stream;

/** @author Spasi */
public interface RenderStream {

	StreamHandler getHandler();

	void bind();

	void swapBuffers();

	void destroy();

}