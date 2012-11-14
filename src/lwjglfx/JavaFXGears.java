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

import java.net.URL;
import java.util.concurrent.CountDownLatch;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.EventHandler;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.layout.Pane;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

/** The JavaFX application entry point */
public class JavaFXGears extends Application {

	private final CountDownLatch runningLatch = new CountDownLatch(1);

	public JavaFXGears() {
	}

	public static void main(String[] args) {
		Application.launch(args);
	}

	@Override
	public void start(final Stage stage) {
		stage.setTitle("JavaFX Window");

		stage.setMinWidth(640);
		stage.setMinHeight(480);

		stage.getIcons().add(new Image("lwjgl_32x32.png"));

		final Screen screen = Screen.getPrimary();
		final Rectangle2D screenBounds = screen.getVisualBounds();

		if ( screenBounds.getWidth() < stage.getWidth() || screenBounds.getHeight() < stage.getHeight() ) {
			stage.setX(screenBounds.getMinX());
			stage.setY(screenBounds.getMinY());

			stage.setWidth(screenBounds.getWidth());
			stage.setHeight(screenBounds.getHeight());
		}

		final URL fxmlURL = getClass().getClassLoader().getResource("gears.fxml");
		final FXMLLoader fxmlLoader = new FXMLLoader(fxmlURL);

		Pane content;
		try {
			content = (Pane)fxmlLoader.load();
		} catch (Exception e) {
			e.printStackTrace();
			System.exit(-1);
			return;
		}

		final GUIController controller = fxmlLoader.getController();

		try {
			final Scene scene = new Scene(content);
			scene.getStylesheets().add(getClass().getClassLoader().getResource("gears.css").toExternalForm());

			stage.setScene(scene);
			stage.show();
		} catch (Exception e) {
			e.printStackTrace();
			System.exit(-1);
			return;
		}

		stage.setOnCloseRequest(new EventHandler<WindowEvent>() {
			public void handle(final WindowEvent e) {
				e.consume();
				runningLatch.countDown();
			}
		});

		new Thread("LWJGL Renderer") {
			public void run() {
				controller.runGears(runningLatch);
				Platform.runLater(new Runnable() {
					public void run() {
						stage.close();
					}
				});
			}
		}.start();
	}

}