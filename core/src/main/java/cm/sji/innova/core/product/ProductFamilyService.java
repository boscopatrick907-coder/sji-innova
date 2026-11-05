package cm.sji.innova.core.product;

import java.sql.SQLException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;

/** Shop-scoped family browsing and catalog search. No stock aggregation across units. */
public final class ProductFamilyService {
    private final ProductFamilyRepository families;
    private final ProductService products;
    public ProductFamilyService(ProductFamilyRepository families, ProductService products) {
        this.families = Objects.requireNonNull(families); this.products = Objects.requireNonNull(products);
    }
    public ProductFamily create(UUID shopId, String name, String category) throws SQLException {
        var family = new ProductFamily(UUID.randomUUID(), shopId, name, category, Instant.now());
        families.insert(family); return family;
    }
    public List<ProductFamily> list(UUID shopId) throws SQLException { return families.findByShop(Objects.requireNonNull(shopId)); }
    private ProductFamily requireFamily(UUID shopId, UUID familyId) throws SQLException {
        Objects.requireNonNull(familyId);
        return list(shopId).stream().filter(f -> f.id().equals(familyId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Family not found in this shop"));
    }
    public Product createVariant(UUID shopId, UUID familyId, String name, long sellingPrice,
                                 String modelReference, Long buyingPrice, String category, String unit,
                                 Map<String,String> specifications) throws SQLException {
        ProductFamily family = requireFamily(shopId, familyId);
        return products.createVariant(shopId, familyId, name, sellingPrice, modelReference, buyingPrice,
                category == null || category.isBlank() ? family.category() : category, unit, specifications);
    }
    public void assign(UUID shopId, UUID productId, UUID familyId) throws SQLException {
        requireFamily(shopId, familyId); families.assign(shopId, productId, familyId);
    }
    public List<Product> variants(UUID shopId, UUID familyId) throws SQLException {
        requireFamily(shopId, familyId);
        return products.list(shopId).stream().filter(p -> familyId.equals(p.familyId())).toList();
    }
    public List<Product> ungrouped(UUID shopId) throws SQLException {
        return products.list(shopId).stream().filter(p -> p.familyId() == null).toList();
    }
    private static String normalized(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
    }
    /** All whitespace-separated tokens must match, ignoring case and accents. Blank returns all. */
    public List<Product> search(UUID shopId, String query) throws SQLException {
        String[] tokens = normalized(Objects.requireNonNull(query)).strip().split("\\s+");
        Map<UUID,ProductFamily> byId = new HashMap<>();
        for (var f : list(shopId)) byId.put(f.id(),f);
        return products.list(shopId).stream().filter(p -> {
            var family = byId.get(p.familyId());
            String text = normalized(p.name()+" "+Objects.toString(p.reference(),"")+" "+
                    Objects.toString(p.modelReference(),"")+" "+Objects.toString(p.category(),"")+" "+
                    p.specifications()+" "+(family == null ? "" : family.name()+" "+family.category()));
            return Arrays.stream(tokens).allMatch(text::contains);
        }).toList();
    }
    /** Existing values are suggestions, never a fixed category list. */
    public List<String> categories(UUID shopId) throws SQLException {
        TreeMap<String,String> values = new TreeMap<>();
        for (var f : list(shopId)) if (f.category()!=null) values.putIfAbsent(normalized(f.category()),f.category());
        for (var p : products.list(shopId)) if(p.category()!=null) values.putIfAbsent(normalized(p.category()),p.category());
        return List.copyOf(values.values());
    }
    public Map<String,List<String>> specificationSuggestions(UUID shopId, UUID familyId) throws SQLException {
        Map<String,TreeSet<String>> values = new TreeMap<>();
        for (var p : variants(shopId,familyId)) p.specifications().forEach((k,v)->values.computeIfAbsent(k,key->new TreeSet<>()).add(v));
        Map<String,List<String>> result = new TreeMap<>();
        values.forEach((k,v)->result.put(k,List.copyOf(v))); return Collections.unmodifiableMap(result);
    }
}
