package com.vicinity24.core.linkedstore.api.automatic.product.load.service;

import com.vicinity24.core.linkedstore.api.automatic.product.load.dto.ProductLoadRequest;
import com.vicinity24.core.linkedstore.api.automatic.product.load.parsers.CsvProductParser;
import com.vicinity24.core.linkedstore.api.automatic.product.load.parsers.JsonProductParser;
import com.vicinity24.core.linkedstore.api.automatic.product.load.parsers.XmlProductParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductTemplateService {

    private final JsonProductParser jsonParser;
    private final CsvProductParser csvParser;
    private final XmlProductParser xmlParser;
    private final ProductImageResolver imageResolver;

    public String jsonTemplate() throws Exception {
        return jsonParser.writeTemplate(buildSamples());
    }

    public String csvTemplate() throws Exception {
        return csvParser.writeTemplate(buildSamples());
    }

    public String xmlTemplate() throws Exception {
        return xmlParser.writeTemplate(buildSamples());
    }

    /**
     * Build example rows that actually reference files from the
     * {@code frontend/products} folder you added — so the user can upload a
     * template and immediately get images wired into the catalog without
     * manual URL fixing.
     */
    public List<ProductLoadRequest> buildSamples() {
        List<ProductLoadRequest> out = new ArrayList<>();
        List<String> productDirs = discoverProductDirs();

        String customCover = imageReference(productDirs, 0, "cover.png");
        String custom1 = imageReference(productDirs, 0, "1791106383.png");
        String custom2 = imageReference(productDirs, 0, "1791106389.png");
        String custom3 = imageReference(productDirs, 0, "1791106393.png");
        out.add(ProductLoadRequest.builder()
                .title("Nike Air Max Pulse — Running")
                .description("Cushioned daily runner with breathable knit upper, responsive Air Max heel unit, durable rubber outsole.")
                .sku("airmax-pulse-wht-42")
                .retailPrice("14900")
                .wholesalePrice("9800")
                .stockQuantity("12")
                .status("ACTIVE")
                .primaryImage(firstNonBlank(customCover, custom1))
                .thumbnailImage(custom1)
                .galleryImages(List.of(
                        firstNonBlank(custom1, customCover),
                        firstNonBlank(custom2, customCover),
                        firstNonBlank(custom3, customCover)
                ))
                .variantImage(firstNonBlank(custom1, customCover))
                .category("Footwear")
                .brand("Nike")
                .gender("Unisex")
                .sport("Running")
                .color("White / Crimson")
                .size("EU 42")
                .build());

        String p2Cover = imageReference(productDirs, 1, "cover.png");
        String p2Img = imageReference(productDirs, 1, "1791106404.png");
        out.add(ProductLoadRequest.builder()
                .title("Nike Air Force 1 Low — White on White")
                .description("Classic leather low-top with cupsole construction. Timeless silhouette.")
                .sku("af1-ww-43")
                .retailPrice("11500")
                .wholesalePrice("7800")
                .stockQuantity("8")
                .status("ACTIVE")
                .primaryImage(firstNonBlank(p2Cover, p2Img))
                .thumbnailImage(p2Img)
                .galleryImages(List.of(firstNonBlank(p2Img, p2Cover)))
                .variantImage(firstNonBlank(p2Img, p2Cover))
                .category("Footwear")
                .brand("Nike")
                .gender("Unisex")
                .sport("Lifestyle")
                .color("Triple White")
                .size("EU 43")
                .build());

        String p3Cover = imageReference(productDirs, 2, "cover.png");
        String p3a = imageReference(productDirs, 2, "1791106414.png");
        String p3b = imageReference(productDirs, 2, "1791106419.png");
        String p3c = imageReference(productDirs, 2, "1791106424.png");
        out.add(ProductLoadRequest.builder()
                .title("Adidas Ultraboost Light")
                .description("Lightweight Boost foam running shoe with Primeknit+ upper, Continental rubber outsole.")
                .sku("ub-light-slv-44")
                .retailPrice("18900")
                .wholesalePrice("12800")
                .stockQuantity("4")
                .status("ACTIVE")
                .primaryImage(firstNonBlank(p3Cover, p3a))
                .thumbnailImage(p3a)
                .galleryImages(List.of(
                        firstNonBlank(p3a, p3Cover),
                        firstNonBlank(p3b, p3Cover),
                        firstNonBlank(p3c, p3Cover)
                ))
                .variantImage(firstNonBlank(p3a, p3Cover))
                .category("Footwear")
                .brand("Adidas")
                .gender("Unisex")
                .sport("Running")
                .color("Halo Silver")
                .size("EU 44")
                .build());

        String p4Cover = imageReference(productDirs, 3, "cover.png");
        String p4Img = imageReference(productDirs, 3, "1791106434.png");
        out.add(ProductLoadRequest.builder()
                .title("Nike Tech Fleece Windrunner Hoodie")
                .description("Signature chevron zip hoodie in double-faced Tech Fleece. Slim fit, warm yet breathable.")
                .sku("tf-wind-blk-L")
                .retailPrice("13900")
                .wholesalePrice("8500")
                .stockQuantity("6")
                .status("ACTIVE")
                .primaryImage(firstNonBlank(p4Cover, p4Img))
                .thumbnailImage(p4Img)
                .galleryImages(List.of(firstNonBlank(p4Img, p4Cover)))
                .variantImage(firstNonBlank(p4Img, p4Cover))
                .category("Apparel")
                .brand("Nike")
                .gender("Unisex")
                .color("Black")
                .size("L")
                .build());

        String p5Cover = imageReference(productDirs, 4, "cover.png");
        String p5a = imageReference(productDirs, 4, "1791106443.png");
        String p5b = imageReference(productDirs, 4, "1791106448.png");
        String p5c = imageReference(productDirs, 4, "1791106453.png");
        out.add(ProductLoadRequest.builder()
                .title("New Era 9FORTY — MLB Yankees Cap")
                .description("Adjustable cotton twill cap with stitched NY logo, curved brim.")
                .sku("newera-940-yankees")
                .retailPrice("3200")
                .wholesalePrice("1900")
                .stockQuantity("20")
                .status("ACTIVE")
                .primaryImage(firstNonBlank(p5Cover, p5a))
                .thumbnailImage(p5a)
                .galleryImages(List.of(
                        firstNonBlank(p5a, p5Cover),
                        firstNonBlank(p5b, p5Cover),
                        firstNonBlank(p5c, p5Cover)
                ))
                .variantImage(firstNonBlank(p5a, p5Cover))
                .category("Accessories")
                .brand("New Era")
                .gender("Unisex")
                .color("Navy")
                .size("Adjustable")
                .build());

        return out;
    }

    private List<String> discoverProductDirs() {
        List<String> names = new ArrayList<>(List.of("p1", "p2", "p3", "p4", "p5"));
        Path root = imageResolver.getFrontendProductsRoot();
        if (Files.isDirectory(root)) {
            try (Stream<Path> s = Files.list(root)) {
                List<String> discovered = s.filter(Files::isDirectory)
                        .map(p -> p.getFileName() == null ? "" : p.getFileName().toString())
                        .filter(n -> !n.isBlank())
                        .toList();
                if (!discovered.isEmpty()) names = discovered;
            } catch (IOException e) {
                log.warn("ProductTemplateService: could not list {}", root, e);
            }
        }
        return names;
    }

    private static String imageReference(List<String> productDirs, int index, String filename) {
        String dir = index < productDirs.size() ? productDirs.get(index) : ("p" + (index + 1));
        return dir + "/" + filename;
    }

    private static String firstNonBlank(String a, String b) {
        return (a == null || a.isBlank()) ? b : a;
    }
}
