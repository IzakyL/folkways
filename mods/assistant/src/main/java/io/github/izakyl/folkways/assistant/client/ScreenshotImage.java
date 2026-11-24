package io.github.izakyl.folkways.assistant.client;

import com.google.gson.JsonObject;
import io.github.izakyl.folkways.assistant.tool.api.CaptureRequest;
import io.github.izakyl.jpi.agent.AgentToolResult;
import io.github.izakyl.jpi.ai.ContentBlock;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.MemoryCacheImageOutputStream;

final class ScreenshotImage {

    private ScreenshotImage() {
    }

    static AgentToolResult encode(BufferedImage source) throws IOException {
        return encode(source, "");
    }

    static AgentToolResult encode(BufferedImage source, String pose) throws IOException {
        return encode(source, pose, CaptureRequest.FULL_SIZE);
    }

    static AgentToolResult encode(BufferedImage source, String pose, int longest) throws IOException {
        double scale = Math.min(1.0, (double) longest / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        var writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("JPEG encoder unavailable");
        }
        var writer = writers.next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var output = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(output);
            var parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(0.8F);
            writer.write(null, new IIOImage(image, null, null), parameters);
        } finally {
            writer.dispose();
        }
        JsonObject data = new JsonObject();
        data.addProperty("ok", true);
        data.addProperty("width", width);
        data.addProperty("height", height);
        data.addProperty("mime_type", "image/jpeg");
        if (!pose.isEmpty()) {
            data.addProperty("pose", pose);
        }
        return new AgentToolResult(List.of(new ContentBlock.Text(data.toString()),
                new ContentBlock.Image(Base64.getEncoder().encodeToString(bytes.toByteArray()), "image/jpeg")), data);
    }

    static AgentToolResult error(String code) {
        JsonObject data = new JsonObject();
        data.addProperty("ok", false);
        data.addProperty("error", code);
        return new AgentToolResult(List.of(new ContentBlock.Text(data.toString())), data, true);
    }
}
