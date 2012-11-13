/*
 * Copyright (c) 2002-2012 LWJGL Project
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * * Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 *
 * * Neither the name of 'LWJGL' nor the names of
 *   its contributors may be used to endorse or promote products derived
 *   from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package lwjglfx;

import org.lwjgl.BufferUtils;
import org.lwjgl.MemoryUtil;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GLContext;
import org.lwjgl.opengl.GLSync;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;

import sun.misc.Unsafe;

import static org.lwjgl.opengl.AMDPinnedMemory.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL32.*;

/** Base functionality for streaming PBO updates. */
abstract class StreamPBO {

	// Low: Less memory usage, less concurrency, less transfers behind
	// High: More memory usages, more concurrency, more transfers behind
	protected final int transfersToBuffer; // 3 provides optimal concurrency in most cases

	protected final int[]        pbos;
	protected final ByteBuffer[] pinnedBuffers;

	protected final GLSync[]         fences; // Required for PBO mapping synchronization
	protected final CountDownLatch[] latches; // Required for synchronization with the processing thread

	protected final boolean USE_AMD_PINNED_MEMORY;

	protected int width;
	protected int height;

	protected long bufferIndex;

	protected StreamPBO(final int transfersToBuffer) {
		final ContextCapabilities caps = GLContext.getCapabilities();

		if ( !caps.OpenGL15 )
			throw new UnsupportedOperationException("Support for OpenGL 1.5 or higher is required.");

		if ( !(caps.OpenGL21 || caps.GL_ARB_pixel_buffer_object || caps.GL_EXT_pixel_buffer_object) )
			throw new UnsupportedOperationException("Support for pixel buffer objects is required.");

		this.transfersToBuffer = transfersToBuffer;

		pbos = new int[transfersToBuffer];
		pinnedBuffers = new ByteBuffer[transfersToBuffer];
		latches = new CountDownLatch[transfersToBuffer];

		USE_AMD_PINNED_MEMORY = caps.GL_AMD_pinned_memory && (caps.OpenGL32 || caps.GL_ARB_sync);

		fences = USE_AMD_PINNED_MEMORY ? new GLSync[transfersToBuffer] : null;
	}

	protected void resizeBuffers(final int width, final int height, final int pboTarget, final int pboUsage) {
		// Setup buffers

		final int renderBytes = width * height * 4;
		final int bufferTarget = USE_AMD_PINNED_MEMORY
		                         ? GL_EXTERNAL_VIRTUAL_MEMORY_BUFFER_AMD
		                         : pboTarget;

		for ( int i = 0; i < pbos.length; i++ ) {
			pbos[i] = glGenBuffers();

			glBindBuffer(bufferTarget, pbos[i]);

			if ( USE_AMD_PINNED_MEMORY ) {
				// Pre-allocate page-aligned pinned buffers
				final int PAGE_SIZE = PageSizeProvider.PAGE_SIZE;

				final ByteBuffer buffer = pinnedBuffers[i] = BufferUtils.createByteBuffer(renderBytes + PAGE_SIZE);
				final int pageOffset = (int)(MemoryUtil.getAddress(buffer) % PAGE_SIZE);
				buffer.position(PAGE_SIZE - pageOffset); // Aligns to page
				buffer.limit(buffer.capacity() - pageOffset); // Caps remaining() to renderBytes

				glBufferData(bufferTarget, buffer, pboUsage);
			} else {
				pinnedBuffers[i] = null;
				glBufferData(bufferTarget, renderBytes, pboUsage);
			}
		}

		glBindBuffer(bufferTarget, 0);
	}

	protected void waitOnLatch(final int index) {
		try {
			latches[index].await();
			latches[index] = null;
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	protected final void waitOnFence(final int index) {
		glClientWaitSync(fences[index], 0, GL_TIMEOUT_IGNORED);
		glDeleteSync(fences[index]);
		fences[index] = null;
	}

	private static class PageSizeProvider {

		static final int PAGE_SIZE;

		static {
			int pageSize = 4096; // Assume 4kb if Unsafe is not available

			try {
				pageSize = getUnsafeInstance().pageSize();
			} catch (Exception e) {
				// ignore
			}

			PAGE_SIZE = pageSize;
		}

		private static Unsafe getUnsafeInstance() {
			final Field[] fields = Unsafe.class.getDeclaredFields();

			/*
			Different runtimes use different names for the Unsafe singleton,
			so we cannot use .getDeclaredField and we scan instead. For example:

			Oracle: theUnsafe
			PERC : m_unsafe_instance
			Android: THE_ONE
			*/
			for ( Field field : fields ) {
				if ( !field.getType().equals(Unsafe.class) )
					continue;

				final int modifiers = field.getModifiers();
				if ( !(Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers)) )
					continue;

				field.setAccessible(true);
				try {
					return (Unsafe)field.get(null);
				} catch (IllegalAccessException e) {
					// ignore
				}
				break;
			}

			throw new UnsupportedOperationException();
		}
	}

}