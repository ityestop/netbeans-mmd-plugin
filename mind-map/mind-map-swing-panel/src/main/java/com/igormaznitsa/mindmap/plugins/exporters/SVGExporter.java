/*
 * Copyright 2015 Igor Maznitsa.
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
package com.igormaznitsa.mindmap.plugins.exporters;

import static com.igormaznitsa.mindmap.swing.panel.MindMapPanel.calculateSizeOfMapInPixels;
import static com.igormaznitsa.mindmap.swing.panel.MindMapPanel.drawOnGraphicsForConfiguration;
import static com.igormaznitsa.mindmap.swing.panel.MindMapPanel.layoutFullDiagramWithCenteringToPaper;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.Dimension2D;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import com.igormaznitsa.mindmap.plugins.api.AbstractExporter;
import com.igormaznitsa.mindmap.model.Topic;
import com.igormaznitsa.mindmap.swing.panel.MindMapPanel;
import java.io.IOException;
import java.io.OutputStream;

import java.text.DecimalFormat;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import com.igormaznitsa.mindmap.swing.panel.Texts;
import com.igormaznitsa.mindmap.swing.services.IconID;
import com.igormaznitsa.mindmap.swing.services.ImageIconServiceProvider;
import com.igormaznitsa.meta.annotation.MustNotContainNull;
import javax.swing.Icon;
import javax.swing.JCheckBox;
import javax.swing.JPanel;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringEscapeUtils;
import com.igormaznitsa.meta.common.utils.Assertions;
import com.igormaznitsa.mindmap.model.MindMap;
import com.igormaznitsa.mindmap.model.logger.Logger;
import com.igormaznitsa.mindmap.model.logger.LoggerFactory;
import com.igormaznitsa.mindmap.swing.panel.MindMapPanelConfig;
import com.igormaznitsa.mindmap.swing.panel.ui.gfx.Gfx;
import com.igormaznitsa.mindmap.swing.panel.ui.gfx.StrokeType;
import com.igormaznitsa.mindmap.swing.panel.utils.MindMapUtils;
import com.igormaznitsa.mindmap.swing.panel.utils.Utils;
import com.igormaznitsa.mindmap.swing.services.UIComponentFactory;
import com.igormaznitsa.mindmap.swing.services.UIComponentFactoryProvider;

public class SVGExporter extends AbstractExporter {

  private static final Logger LOGGER = LoggerFactory.getLogger(SVGExporter.class);
  private static final UIComponentFactory UI_FACTORY = UIComponentFactoryProvider.findInstance();

  private static boolean flagExpandAllNodes = false;
  private static boolean flagSaveBackground = true;

  private static final Icon ICO = ImageIconServiceProvider.findInstance().getIconForId(IconID.POPUP_EXPORT_SVG);

  private static final String SVG_HEADER = "<svg version=\"1.1\" baseProfile=\"tiny\" id=\"svg-root\" width=\"%d%%\" height=\"%d%%\" viewBox=\"0 0 %s %s\" xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\">";
  private static final String NEXT_LINE = "\n";
  private static final DecimalFormat DOUBLE = new DecimalFormat("#.###");

  private static final class SVGGraphics implements Gfx {

    private final StringBuilder buffer;
    private final Graphics2D context;

    private double translateX;
    private double translateY;

    private float strokeWidth = 1.0f;
    private StrokeType strokeType = StrokeType.SOLID;

    private static final DecimalFormat ALPHA = new DecimalFormat("#.##");

    @Nonnull
    private static String svgRgb(@Nonnull final Color color) {
        return "rgb(" + color.getRed() + ',' + color.getGreen() + ',' + color.getBlue() + ')';
    }

    private void printFillOpacity(@Nonnull final Color color){
      if (color.getAlpha()<255){
        this.buffer.append(" fill-opacity=\"").append(ALPHA.format(color.getAlpha() / 255.0f)).append("\" ");
      }
    }
    
    private void printFontData() {
      final Font font = this.context.getFont();
      final int style = font.getStyle();

      this.buffer.append("font-size=\"").append(dbl2str(font.getSize2D())).append("\" font-family=\"").append(StringEscapeUtils.escapeXml(font.getFamily())).append("\" font-weight=\"")
          .append((style & Font.BOLD) == 0 ? "normal" : "bold").append("\" font-style=\"").append((style & Font.ITALIC) == 0 ? "normal" : "italic").append('\"');
    }

    private void printStrokeData(@Nonnull final Color color) {
      this.buffer.append(" stroke=\"").append(svgRgb(color))
          .append("\" stroke-width=\"").append(dbl2str(this.strokeWidth)).append("\"");

      switch (this.strokeType) {
        case SOLID:
          this.buffer.append(" stroke-linecap=\"round\"");
          break;
        case DASHES:
          this.buffer.append(" stroke-linecap=\"round\" stroke-dasharray=\"").append(dbl2str(this.strokeWidth * 5.0f)).append(',').append(dbl2str(this.strokeWidth * 2.0f)).append("\"");
          break;
        case DOTS:
          this.buffer.append(" stroke-linecap=\"butt\" stroke-dasharray=\"").append(dbl2str(this.strokeWidth)).append(',').append(dbl2str(this.strokeWidth * 2.0f)).append("\"");
          break;
      }
    }

    private SVGGraphics(@Nonnull final StringBuilder buffer, @Nonnull final Graphics2D context) {
      this.buffer = buffer;
      this.context = (Graphics2D) context.create();
    }

    @Override
    public float getFontMaxAscent() {
      return this.context.getFontMetrics().getMaxAscent();
    }

    @Override
    @Nonnull
    public Rectangle2D getStringBounds(@Nonnull final String s) {
      return this.context.getFontMetrics().getStringBounds(s, this.context);
    }

    @Override
    public void setClip(final int x, final int y, final int w, final int h) {
      this.context.setClip(x, y, w, h);
    }

    @Override
    @Nonnull
    public Gfx copy() {
      final SVGGraphics result = new SVGGraphics(this.buffer, this.context);
      result.translateX = this.translateX;
      result.translateY = this.translateY;
      result.strokeType = this.strokeType;
      result.strokeWidth = this.strokeWidth;
      return result;
    }

    @Override
    public void dispose() {
      this.context.dispose();
    }

    @Override
    public void translate(final double x, final double y) {
      this.translateX += x;
      this.translateY += y;
      this.context.translate(x, y);
    }

    @Override
    @Nullable
    public Rectangle getClipBounds() {
      return this.context.getClipBounds();
    }

    @Override
    public void setStroke(final float width, @Nonnull final StrokeType type) {
      if (type != this.strokeType || Float.compare(this.strokeWidth, width) != 0) {
        this.strokeType = type;
        this.strokeWidth = width;
        if (type != this.strokeType || Float.compare(this.strokeWidth, width) != 0) {
          this.strokeType = type;
          this.strokeWidth = width;

          final Stroke stroke;

          switch (type) {
            case SOLID:
              stroke = new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_MITER);
              break;
            case DASHES:
              stroke = new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10.0f, new float[]{width * 5.0f, width * 2.0f}, 0.0f);
              break;
            case DOTS:
              stroke = new BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, new float[]{width, width * 2.0f}, 0.0f);
              break;
            default:
              throw new Error("Unexpected stroke type : " + type);
          }
          this.context.setStroke(stroke);
        }
      }
    }

    @Override
    public void drawLine(final int startX, final int startY, final int endX, final int endY, @Nullable final Color color) {
      final Stroke stroke = this.context.getStroke();
      this.buffer.append("<line x1=\"").append(dbl2str(startX + this.translateX))
          .append("\" y1=\"").append(dbl2str(startY + this.translateY))
          .append("\" x2=\"").append(dbl2str(endX + this.translateX))
          .append("\" y2=\"").append(dbl2str(endY + this.translateY)).append("\" ");
      if (color != null) {
        printStrokeData(color);
        printFillOpacity(color);
      }
      this.buffer.append("/>").append(NEXT_LINE);
    }

    @Override
    public void drawString(@Nonnull final String text, final int x, final int y, @Nullable final Color color) {
      this.buffer.append("<text x=\"").append(dbl2str(this.translateX + x)).append("\" y=\"").append(dbl2str(this.translateY + y)).append('\"');
      if (color != null) {
        this.buffer.append(" fill=\"").append(svgRgb(color)).append("\"");
        printFillOpacity(color);
      }
      this.buffer.append(' ');
      printFontData();
      this.buffer.append('>').append(StringEscapeUtils.escapeXml(text)).append("</text>").append(NEXT_LINE);
    }

    @Override
    public void drawRect(final int x, final int y, final int width, final int height, final @Nullable Color border, final @Nullable Color fill) {
      this.buffer.append("<rect x=\"").append(dbl2str(this.translateX + x))
          .append("\" y=\"").append(dbl2str(translateY + y))
          .append("\" width=\"").append(dbl2str(width))
          .append("\" height=\"").append(dbl2str(height))
          .append("\" ");
      if (border != null) {
        printStrokeData(border);
      }

      if (fill == null) {
        this.buffer.append(" fill=\"none\"");
      } else {
        this.buffer.append(" fill=\"").append(svgRgb(fill)).append("\"");
        printFillOpacity(fill);
      }

      this.buffer.append("/>").append(NEXT_LINE);
    }

    @Override
    public void draw(@Nonnull final Shape shape, @Nullable final Color border, @Nullable final Color fill) {
      if (shape instanceof RoundRectangle2D) {
        final RoundRectangle2D rect = (RoundRectangle2D) shape;

        this.buffer.append("<rect x=\"").append(dbl2str(this.translateX + rect.getX()))
            .append("\" y=\"").append(dbl2str(translateY + rect.getY()))
            .append("\" width=\"").append(dbl2str(rect.getWidth()))
            .append("\" height=\"").append(dbl2str(rect.getHeight()))
            .append("\" rx=\"").append(dbl2str(rect.getArcWidth() / 2.0d))
            .append("\" ry=\"").append(dbl2str(rect.getArcHeight() / 2.0d))
            .append("\" ");

      } else if (shape instanceof Rectangle2D) {

        final Rectangle2D rect = (Rectangle2D) shape;
        this.buffer.append("<rect x=\"").append(dbl2str(this.translateX + rect.getX()))
            .append("\" y=\"").append(dbl2str(translateY + rect.getY()))
            .append("\" width=\"").append(dbl2str(rect.getWidth()))
            .append("\" height=\"").append(dbl2str(rect.getHeight()))
            .append("\" ");

      } else if (shape instanceof Path2D) {
        final Path2D path = (Path2D) shape;
        final double[] data = new double[6];

        this.buffer.append("<path d=\"");

        boolean nofirst = false;

        for (final PathIterator pi = path.getPathIterator(null); !pi.isDone(); pi.next()) {
          if (nofirst) {
            this.buffer.append(' ');
          }
          switch (pi.currentSegment(data)) {
            case PathIterator.SEG_MOVETO: {
              this.buffer.append("M ").append(dbl2str(this.translateX + data[0])).append(' ').append(dbl2str(this.translateY + data[1]));
            }
            break;
            case PathIterator.SEG_LINETO: {
              this.buffer.append("L ").append(dbl2str(this.translateX + data[0])).append(' ').append(dbl2str(this.translateY + data[1]));
            }
            break;
            case PathIterator.SEG_CUBICTO: {
              this.buffer.append("C ")
                  .append(dbl2str(this.translateX + data[0])).append(' ').append(dbl2str(this.translateY + data[1])).append(',')
                  .append(dbl2str(this.translateX + data[2])).append(' ').append(dbl2str(this.translateY + data[3])).append(',')
                  .append(dbl2str(this.translateX + data[4])).append(' ').append(dbl2str(this.translateY + data[5]));
            }
            break;
            case PathIterator.SEG_QUADTO: {
              this.buffer.append("Q ")
                  .append(dbl2str(this.translateX + data[0])).append(' ').append(dbl2str(this.translateY + data[1])).append(',')
                  .append(dbl2str(this.translateX + data[2])).append(' ').append(dbl2str(this.translateY + data[3]));
            }
            break;
            case PathIterator.SEG_CLOSE: {
              this.buffer.append("Z");
            }
            break;
            default:
              LOGGER.warn("Unexpected path segment type");
          }
          nofirst = true;
        }
        this.buffer.append("\" ");
      } else {
        LOGGER.warn("Detected unexpected shape : " + shape.getClass().getName());
      }

      if (border != null) {
        printStrokeData(border);
      }

      if (fill == null) {
        this.buffer.append(" fill=\"none\"");
      } else {
        this.buffer.append(" fill=\"").append(svgRgb(fill)).append("\"");
        printFillOpacity(fill);
      }

      this.buffer.append("/>").append(NEXT_LINE);
    }

    @Override
    public void drawCurve(final double startX, final double startY, final double endX, final double endY, @Nullable final Color color) {
      this.buffer.append("<path d=\"M").append(dbl2str(startX + this.translateX)).append(',').append(startY + this.translateY)
          .append(" C").append(dbl2str(startX))
          .append(',').append(dbl2str(endY))
          .append(' ').append(dbl2str(startX))
          .append(',').append(dbl2str(endY))
          .append(' ').append(dbl2str(endX))
          .append(',').append(dbl2str(endY))
          .append("\" fill=\"none\"");

      if (color != null) {
        printStrokeData(color);
      }
      this.buffer.append(" />").append(NEXT_LINE);
    }

    @Override
    public void drawOval(final int x, final int y, final int w, final int h, @Nullable final Color border, @Nullable final Color fill) {
      final double rx = (double) w / 2.0d;
      final double ry = (double) h / 2.0d;
      final double cx = (double) x + this.translateX + rx;
      final double cy = (double) y + this.translateY + ry;

      this.buffer.append("<ellipse cx=\"").append(dbl2str(cx))
          .append("\" cy=\"").append(dbl2str(cy))
          .append("\" rx=\"").append(dbl2str(rx))
          .append("\" ry=\"").append(dbl2str(ry))
          .append("\" ");

      if (border != null) {
        printStrokeData(border);
      }

      if (fill == null) {
        this.buffer.append(" fill=\"none\"");
      } else {
        this.buffer.append(" fill=\"").append(svgRgb(fill)).append("\"");
        printFillOpacity(fill);
      }

      this.buffer.append("/>").append(NEXT_LINE);
    }

    @Override
    public void drawImage(@Nonnull final Image image, final int x, final int y) {
      if (image instanceof RenderedImage) {
        final RenderedImage ri = (RenderedImage) image;
        final ByteArrayOutputStream imageBuffer = new ByteArrayOutputStream(1024);
        try {
          if (ImageIO.write(ri, "png", imageBuffer)) {
            this.buffer.append("<image width=\"").append(ri.getWidth()).append("\" height=\"").append(ri.getHeight()).append("\" x=\"").append(dbl2str(this.translateX+x)).append("\" y=\"").append(dbl2str(this.translateY+y)).append("\" xlink:href=\"data:image/png;base64,");
            this.buffer.append(Utils.base64encode(imageBuffer.toByteArray()));
            this.buffer.append("\"/>").append(NEXT_LINE);
          } else {
            LOGGER.warn("Can't place image because PNG writer is not found");
          }
        } catch (IOException ex) {
          LOGGER.error("Can't place image for error", ex);
        }
      } else {
        LOGGER.warn("Can't place image because it is not rendered one : " + image.getClass().getName());
      }
    }

    @Override
    public void setFont(@Nonnull final Font font) {
      this.context.setFont(font);
    }

  }

  @Override
  @Nullable
  public JComponent makeOptions() {
    final JPanel panel = UI_FACTORY.makePanel();
    final JCheckBox checkBoxExpandAll = UI_FACTORY.makeCheckBox();
    checkBoxExpandAll.setSelected(flagExpandAllNodes);
    checkBoxExpandAll.setText(Texts.getString("SvgExporter.optionUnfoldAll"));
    checkBoxExpandAll.setActionCommand("unfold");

    final JCheckBox checkSaveBackground = UI_FACTORY.makeCheckBox();
    checkSaveBackground.setSelected(flagSaveBackground);
    checkSaveBackground.setText(Texts.getString("SvgExporter.optionDrawBackground"));
    checkSaveBackground.setActionCommand("back");

    panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

    panel.add(checkBoxExpandAll);
    panel.add(checkSaveBackground);

    panel.setBorder(BorderFactory.createEmptyBorder(16, 32, 16, 32));

    return panel;
  }

  @Nonnull
  private static String dbl2str(final double value) {
    return DOUBLE.format(value);
  }

  @Override
  public void doExport(@Nonnull final MindMapPanel panel, @Nullable final JComponent options, @Nullable final OutputStream out) throws IOException {
    for (final Component compo : Assertions.assertNotNull(options).getComponents()) {
      if (compo instanceof JCheckBox) {
        final JCheckBox cb = (JCheckBox) compo;
        if ("unfold".equalsIgnoreCase(cb.getActionCommand())) {
          flagExpandAllNodes = cb.isSelected();
        } else if ("back".equalsIgnoreCase(cb.getActionCommand())) {
          flagSaveBackground = cb.isSelected();
        }
      }
    }

    final MindMap workMap = new MindMap(panel.getModel(), null);
    workMap.resetPayload();

    if (flagExpandAllNodes) {
      MindMapUtils.removeCollapseAttr(workMap);
    }
    
    final MindMapPanelConfig newConfig = new MindMapPanelConfig(panel.getConfiguration(), false);
    newConfig.setDrawBackground(flagSaveBackground);
    newConfig.setScale(1.0f);

    final Dimension2D blockSize = calculateSizeOfMapInPixels(workMap, newConfig, flagExpandAllNodes);
    if (blockSize == null) {
      return;
    }

    final StringBuilder buffer = new StringBuilder(16384);
    buffer.append(String.format(SVG_HEADER, 100, 100, dbl2str(blockSize.getWidth()), dbl2str(blockSize.getHeight()))).append(NEXT_LINE);

    final BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
    final Graphics2D g = image.createGraphics();
    final Gfx gfx = new SVGGraphics(buffer, g);

    gfx.setClip(0, 0, (int) Math.round(blockSize.getWidth()), (int) Math.round(blockSize.getHeight()));
    try {
      layoutFullDiagramWithCenteringToPaper(gfx, workMap, newConfig, blockSize);
      drawOnGraphicsForConfiguration(gfx, newConfig, workMap, false, null);
    } finally {
      gfx.dispose();
    }
    buffer.append("</svg>");

    final String text = buffer.toString();

    File fileToSaveMap = null;
    OutputStream theOut = out;
    if (theOut == null) {
      fileToSaveMap = MindMapUtils.selectFileToSaveForFileFilter(panel, Texts.getString("SvgExporter.saveDialogTitle"), ".svg", Texts.getString("SvgExporter.filterDescription"), Texts.getString("SvgExporter.approveButtonText"));
      fileToSaveMap = MindMapUtils.checkFileAndExtension(panel, fileToSaveMap, ".svg");//NOI18N
      theOut = fileToSaveMap == null ? null : new BufferedOutputStream(new FileOutputStream(fileToSaveMap, false));
    }
    if (theOut != null) {
      try {
        IOUtils.write(text, theOut, "UTF-8");
      } finally {
        if (fileToSaveMap != null) {
          IOUtils.closeQuietly(theOut);
        }
      }
    }
  }

  @Override
  @Nonnull
  public String getName(@Nonnull final MindMapPanel panel, @Nullable Topic actionTopic, @Nonnull @MustNotContainNull Topic[] selectedTopics) {
    return Texts.getString("SvgExporter.exporterName");
  }

  @Override
  @Nonnull
  public String getReference(@Nonnull final MindMapPanel panel, @Nullable Topic actionTopic, @Nonnull @MustNotContainNull Topic[] selectedTopics) {
    return Texts.getString("SvgExporter.exporterReference");
  }

  @Override
  @Nonnull
  public Icon getIcon(@Nonnull final MindMapPanel panel, @Nullable Topic actionTopic, @Nonnull @MustNotContainNull Topic[] selectedTopics) {
    return ICO;
  }

  @Override
  public int getOrder() {
    return 5;
  }
}
