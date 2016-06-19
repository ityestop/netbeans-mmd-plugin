/*
 * Copyright 2016 Igor Maznitsa.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.igormaznitsa.sciareto;

import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.io.File;
import com.igormaznitsa.sciareto.ui.MainFrame;
import javax.annotation.Nonnull;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.UIManager.LookAndFeelInfo;
import com.igormaznitsa.commons.version.Version;
import com.igormaznitsa.meta.annotation.MustNotContainNull;
import com.igormaznitsa.mindmap.model.logger.Logger;
import com.igormaznitsa.mindmap.model.logger.LoggerFactory;
import com.igormaznitsa.mindmap.plugins.MindMapPluginRegistry;
import com.igormaznitsa.mindmap.plugins.external.ExternalPlugins;
import com.igormaznitsa.sciareto.plugins.PrinterPlugin;

public class Main {
  
  private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);
  
  private static MainFrame MAIN_FRAME;

  public static final Version IDE_VERSION = new Version("sciareto", new long[]{1L,0L,0L}, null);
  
  private static final String PROPERTY = "nbmmd.plugin.folder";
  
  @Nonnull
  public static MainFrame getApplicationFrame() {
    return MAIN_FRAME;
  }

  private static void loadPlugins(){
    final String pluginFolder = System.getProperty(PROPERTY);
    if (pluginFolder != null) {
      final File folder = new File(pluginFolder);
      if (folder.isDirectory()) {
        LOGGER.info("Loading plugins from folder : " + folder);
        new ExternalPlugins(folder).init();
      } else {
        LOGGER.error("Can't find plugin folder : " + folder);
      }
    } else {
      LOGGER.info("Property " + PROPERTY + " is not defined");
    }  
  }
  
  public static void main(@Nonnull @MustNotContainNull final String... args) {
    try {
      for (LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
        if ("nimbus".equalsIgnoreCase(info.getName())) {
          UIManager.setLookAndFeel(info.getClassName());
          break;
        }
      }
    } catch (Exception e) {
      System.out.println("Can't use NIMBUS");
    }

    loadPlugins();
    
    SwingUtilities.invokeLater(new Runnable() {
      @Override
      public void run() {

        final GraphicsDevice gd = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        final int width = gd.getDisplayMode().getWidth();
        final int height = gd.getDisplayMode().getHeight();

        MindMapPluginRegistry.getInstance().registerPlugin(new PrinterPlugin());
        MAIN_FRAME = new MainFrame(args);
        MAIN_FRAME.setSize(Math.round(width * 0.75f), Math.round(height * 0.75f));

        MAIN_FRAME.setVisible(true);

        SwingUtilities.invokeLater(new Runnable() {
          @Override
          public void run() {
            MAIN_FRAME.setExtendedState(MAIN_FRAME.getExtendedState() | MAIN_FRAME.MAXIMIZED_BOTH);
          }
        });
      }
    });
  }
}