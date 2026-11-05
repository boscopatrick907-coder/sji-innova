package cm.sji.innova.core.product.importer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Versioned, length-prefixed identities: delimiters and nulls cannot alias fields. */
public final class ImportFingerprint {
    private ImportFingerprint() { }
    public static String hash(List<String> fields) {
        try {
            var bytes=new ByteArrayOutputStream();
            try(var out=new DataOutputStream(bytes)) {
                out.writeUTF("cella-import-v2");out.writeInt(fields.size());
                for(String field:fields) {
                    if(field==null){out.writeInt(-1);continue;}
                    byte[] value=field.getBytes(StandardCharsets.UTF_8);out.writeInt(value.length);out.write(value);
                }
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch(IOException|NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    public static String content(List<ParsedRow> rows) {
        List<String> values=new ArrayList<>();
        for(var row:rows) {
            values.add(Integer.toString(row.rowNumber()));values.add(row.rawLine());
            values.add(row.name());values.add(row.modelReference());values.add(row.category());values.add(row.unit());
            values.add(Objects.toString(row.buyingPrice(),null));values.add(Objects.toString(row.sellingPrice(),null));
            values.add(hash(row.errors()));
        }
        return hash(values);
    }
    public static UUID previewId(UUID shop,String hash) {
        return UUID.nameUUIDFromBytes(("preview-v2:"+shop+":"+hash).getBytes(StandardCharsets.UTF_8));
    }
    public static String request(UUID shop,ConfirmedImportRequest request) {
        List<String> values=new ArrayList<>(List.of(shop.toString(),request.previewId().toString(),
                request.contentHash(),Boolean.toString(request.allowDuplicates())));
        request.selectedRowNumbers().stream().sorted().forEach(n->values.add(n.toString()));
        return "request-v2:"+hash(values);
    }
}
