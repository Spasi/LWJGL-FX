package lwjglfx;/*
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

import org.lwjgl.util.stream.StreamHandler;
import org.lwjgl.util.stream.StreamUtil;
import org.lwjgl.util.stream.StreamUtil.RenderStreamFactory;
import org.lwjgl.util.stream.StreamUtil.TextureStreamFactory;

import java.net.URL;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;

import javafx.animation.AnimationTimer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.event.*;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.SnapshotResult;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebEvent;
import javafx.scene.web.WebView;
import javafx.util.Callback;
import javafx.util.Duration;
import javafx.util.StringConverter;

import static javafx.beans.binding.Bindings.*;
import static javafx.collections.FXCollections.*;
import static org.lwjgl.opengl.GL11.*;

/** The JavaFX application GUI controller. */
public class GUIController implements Initializable {

	@FXML private AnchorPane gearsRoot;
	@FXML private ImageView  gearsView;

	@FXML private Label fpsLabel;
	@FXML private Label javaInfoLabel;
	@FXML private Label systemInfoLabel;
	@FXML private Label glInfoLabel;

	@FXML private CheckBox vsync;

	@FXML private ChoiceBox<RenderStreamFactory>  renderChoice;
	@FXML private ChoiceBox<TextureStreamFactory> textureChoice;
	@FXML private ChoiceBox<BufferingChoice>      bufferingChoice;

	@FXML private Slider msaaSamples;

	@FXML private WebView webView;

	private Gears gears;

	public GUIController() {
	}

	public void initialize(final URL url, final ResourceBundle resourceBundle) {
		gearsView.fitWidthProperty().bind(gearsRoot.widthProperty());
		gearsView.fitHeightProperty().bind(gearsRoot.heightProperty());

		final StringBuilder info = new StringBuilder(128);
		info
			.append(System.getProperty("java.vm.name"))
			.append(' ')
			.append(System.getProperty("java.version"))
			.append(' ')
			.append(System.getProperty("java.vm.version"));

		javaInfoLabel.setText(info.toString());

		info.setLength(0);
		info
			.append(System.getProperty("os.name"))
			.append(" - JavaFX ")
			.append(System.getProperty("javafx.runtime.version"));

		systemInfoLabel.setText(info.toString());

		bufferingChoice.setItems(observableArrayList(BufferingChoice.values()));

		msaaSamples.setMin(0);
		msaaSamples.setMax(0);
		if ( System.getProperty("javafx.runtime.version").startsWith("2") )
			// The label formatter was not working until JavaFX 8.
			for ( Node n : msaaSamples.getParent().getChildrenUnmodifiable() ) {
				if ( !(n instanceof Label) )
					continue;

				Label l = (Label)n;
				if ( "MSAA Samples".equals(l.getText()) ) {
					l.setText("MSAA Samples (2^x)");
					break;
				}
			}
		else
			msaaSamples.setLabelFormatter(new StringConverter<Double>() {
				@Override
				public String toString(final Double object) {
					return Integer.toString(1 << object.intValue());
				}

				@Override
				public Double fromString(final String string) {
					return null;
				}
			});
	}

	private StreamHandler getReadHandler() {
		return new StreamHandler() {

			private WritableImage renderImage;

			private long frame;
			private long lastUpload;

			{
				new AnimationTimer() {
					@Override
					public void handle(final long now) {
						frame++;
					}
				}.start();
			}

			public int getWidth() {
				return (int)gearsView.getFitWidth();
			}

			public int getHeight() {
				return (int)gearsView.getFitHeight();
			}

			public void process(final int width, final int height, final ByteBuffer data, final int stride, final Semaphore signal) {
				// This method runs in the background rendering thread
				Platform.runLater(new Runnable() {
					public void run() {
						try {
							// If we're quitting, discard update
							if ( !gearsView.isVisible() )
								return;

							// Detect resize and recreate the image
							if ( renderImage == null || (int)renderImage.getWidth() != width || (int)renderImage.getHeight() != height ) {
								renderImage = new WritableImage(width, height);
								gearsView.setImage(renderImage);
							}

							// Throttling, only update the JavaFX view once per frame.
							// *NOTE*: The +1 is weird here, but apparently setPixels triggers a new pulse within the current frame.
							// If we ignore that, we'd get a) worse performance from uploading double the frames and b) exceptions
							// on certain configurations (e.g. Nvidia GPU with the D3D pipeline).
							if ( frame <= lastUpload + 1 )
								return;

							lastUpload = frame;

							// Upload the image to JavaFX
							PixelWriter pw = renderImage.getPixelWriter();
							pw.setPixels(0, 0, width, height, pw.getPixelFormat(), data, stride);
						} finally {
							// Notify the render thread that we're done processing
							signal.release();
						}
					}
				});
			}
		};
	}

	private StreamHandler getWriteHandler() {
		return new StreamHandler() {

			private WritableImage webImage;

			public int getWidth() {
				return (int)webView.getWidth();
			}

			public int getHeight() {
				return (int)webView.getHeight();
			}

			public void process(final int width, final int height, final ByteBuffer buffer, final int stride, final Semaphore signal) {
				// This method runs in the background rendering thread
				Platform.runLater(new Runnable() {
					public void run() {
						if ( webImage == null || webImage.getWidth() != width || webImage.getHeight() != height )
							webImage = new WritableImage(width, height);

						webView.snapshot(new Callback<SnapshotResult, Void>() {
							public Void call(final SnapshotResult snapshotResult) {
								snapshotResult.getImage().getPixelReader().getPixels(0, 0, width, height, PixelFormat.getByteBgraPreInstance(), buffer, stride);

								signal.release();
								return null;

							}
						}, new SnapshotParameters(), webImage);
					}
				});
			}
		};
	}

	// This method will run in the background rendering thread
	void runGears(final CountDownLatch runningLatch) {
		try {
			gears = new Gears(
				getReadHandler(),
				getWriteHandler()
			);
		} catch (Throwable t) {
			t.printStackTrace();
			return;
		}

		final List<RenderStreamFactory> renderStreamFactories = StreamUtil.getRenderStreamImplementations();
		final List<TextureStreamFactory> textureStreamFactories = StreamUtil.getTextureStreamImplementations();

		final String vendor = glGetString(GL_VENDOR);
		final String version = glGetString(GL_VERSION);

		Platform.runLater(new Runnable() {
			public void run() {
				// Listen for FPS changes and update the fps label
				final ReadOnlyIntegerProperty fps = gears.fpsProperty();

				fpsLabel.textProperty().bind(createStringBinding(new Callable<String>() {
					public String call() throws Exception {
						return "FPS: " + fps.get();
					}
				}, fps));
				glInfoLabel.setText(vendor + " OpenGL " + version);

				renderChoice.setItems(observableList(renderStreamFactories));
				for ( int i = 0; i < renderStreamFactories.size(); i++ ) {
					if ( renderStreamFactories.get(i) == gears.getRenderStreamFactory() ) {
						renderChoice.getSelectionModel().select(i);
						break;
					}
				}
				renderChoice.getSelectionModel().selectedItemProperty().addListener(new ChangeListener<RenderStreamFactory>() {
					public void changed(final ObservableValue<? extends RenderStreamFactory> observableValue, final RenderStreamFactory oldValue, final RenderStreamFactory newValue) {
						gears.setRenderStreamFactory(newValue);
					}
				});

				textureChoice.setItems(observableList(textureStreamFactories));
				for ( int i = 0; i < textureStreamFactories.size(); i++ ) {
					if ( textureStreamFactories.get(i) == gears.getTextureStreamFactory() ) {
						textureChoice.getSelectionModel().select(i);
						break;
					}
				}
				textureChoice.getSelectionModel().selectedItemProperty().addListener(new ChangeListener<TextureStreamFactory>() {
					public void changed(final ObservableValue<? extends TextureStreamFactory> observableValue, final TextureStreamFactory oldValue, final TextureStreamFactory newValue) {
						gears.setTextureStreamFactory(newValue);
					}
				});

				bufferingChoice.getSelectionModel().select(gears.getTransfersToBuffer() - 1);
				bufferingChoice.getSelectionModel().selectedItemProperty().addListener(new ChangeListener<BufferingChoice>() {
					public void changed(final ObservableValue<? extends BufferingChoice> observableValue, final BufferingChoice oldValue, final BufferingChoice newValue) {
						gears.setTransfersToBuffer(newValue.getTransfersToBuffer());
					}
				});

				vsync.selectedProperty().addListener(new ChangeListener<Boolean>() {
					public void changed(final ObservableValue<? extends Boolean> observableValue, final Boolean oldValue, final Boolean newValue) {
						gears.setVsync(newValue);
					}
				});

				final int maxSamples = gears.getMaxSamples();
				if ( maxSamples == 1 )
					msaaSamples.setDisable(true);
				else {
					msaaSamples.setMax(Integer.numberOfTrailingZeros(maxSamples));
					msaaSamples.valueProperty().addListener(new ChangeListener<Number>() {
						public void changed(final ObservableValue<? extends Number> observableValue, final Number oldValue, final Number newValue) {
							gears.setSamples(1 << newValue.intValue());
						}
					});
				}

				// Listen for changes to the WebView contents.
				final ChangeListener<Number> numberListener = new ChangeListener<Number>() {
					public void changed(final ObservableValue<? extends Number> observableValue, final Number oldValue, final Number newValue) {
						gears.updateSnapshot();
					}
				};

				webView.widthProperty().addListener(numberListener);
				webView.heightProperty().addListener(numberListener);

				final WebEngine engine = webView.getEngine();

				engine.getLoadWorker().progressProperty().addListener(numberListener);
				engine.setOnStatusChanged(new EventHandler<WebEvent<String>>() {
					public void handle(final WebEvent<String> e) {
						gears.updateSnapshot();
					}
				});

				webView.setEventDispatcher(new EventDispatcher() {
					private final EventDispatcher parent = webView.getEventDispatcher();

					public Event dispatchEvent(final Event e, final EventDispatchChain dispatchChain) {
						// Mouse over events within the page will be triggered by the StatusChanged handler above.
						if ( e.getEventType() != MouseEvent.MOUSE_MOVED && gears != null )
							gears.updateSnapshot();

						return parent.dispatchEvent(e, dispatchChain);
					}
				});

				// Force an update every 4 frames for carets.
				final Timeline timeline = new Timeline();
				timeline.setCycleCount(Timeline.INDEFINITE);
				timeline.setAutoReverse(true);
				timeline.getKeyFrames().add(new KeyFrame(Duration.millis(4 * (1000 / 60)), new EventHandler<ActionEvent>() {
					public void handle(final ActionEvent e) {
						if ( webView.isFocused() )
							gears.updateSnapshot();
					}
				}));
				timeline.play();

				// Do one last update on focus lost
				webView.focusedProperty().addListener(new ChangeListener<Boolean>() {
					public void changed(final ObservableValue<? extends Boolean> observableValue, final Boolean oldValue, final Boolean newValue) {
						if ( !newValue )
							gears.updateSnapshot();
					}
				});

				webView.getEngine().load("http://www.java-gaming.org");
			}
		});

		gears.execute(runningLatch);
	}

	private enum BufferingChoice {
		SINGLE(1, "No buffering"),
		DOUBLE(2, "Double buffering"),
		TRIPLE(3, "Triple buffering");

		private final int    transfersToBuffer;
		private final String description;

		private BufferingChoice(final int transfersToBuffer, final String description) {
			this.transfersToBuffer = transfersToBuffer;
			this.description = transfersToBuffer + "x - " + description;
		}

		public int getTransfersToBuffer() {
			return transfersToBuffer;
		}

		public String getDescription() {
			return description;
		}

		public String toString() {
			return description;
		}
	}

}