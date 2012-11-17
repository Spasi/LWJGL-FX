package org.lwjgl.util.stream;

/** @author Spasi */
public interface TextureStream {

	StreamHandler getHandler();

	int getWidth();

	int getHeight();

	void snapshot();

	void tick();

	void bind();

	void destroy();

}