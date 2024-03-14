package org.opensearch.common.document;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.opensearch.core.common.text.Text;
import org.opensearch.server.proto.FetchSearchResultProto;

public class ProtobufDocumentFieldSerde implements DocumentFieldFactory<InputStream> {

    private FetchSearchResultProto.SearchHit.DocumentField documentField;

    @Override
    public DocumentField createDocumentField(InputStream inputStream) throws IOException {
        documentField = FetchSearchResultProto.SearchHit.DocumentField.parseFrom(inputStream);
        String name = documentField.getName();
        List<Object> values = new ArrayList<>();
        for (FetchSearchResultProto.DocumentFieldValue value : documentField.getValuesList()) {
            values.add(readDocumentFieldValueFromProtobuf(value));
        }
        return new DocumentField(name, values);
    }

    private Object readDocumentFieldValueFromProtobuf(FetchSearchResultProto.DocumentFieldValue documentFieldValue) throws IOException {
        if (documentFieldValue.hasValueString()) {
            return documentFieldValue.getValueString();
        } else if (documentFieldValue.hasValueInt()) {
            return documentFieldValue.getValueInt();
        } else if (documentFieldValue.hasValueLong()) {
            return documentFieldValue.getValueLong();
        } else if (documentFieldValue.hasValueFloat()) {
            return documentFieldValue.getValueFloat();
        } else if (documentFieldValue.hasValueDouble()) {
            return documentFieldValue.getValueDouble();
        } else if (documentFieldValue.hasValueBool()) {
            return documentFieldValue.getValueBool();
        } else if (documentFieldValue.getValueByteArrayList().size() > 0) {
            return documentFieldValue.getValueByteArrayList().toArray();
        } else if (documentFieldValue.getValueArrayListList().size() > 0) {
            List<Object> list = new ArrayList<>();
            for (FetchSearchResultProto.DocumentFieldValue value : documentFieldValue.getValueArrayListList()) {
                list.add(readDocumentFieldValueFromProtobuf(value));
            }
            return list;
        } else if (documentFieldValue.getValueMapMap().size() > 0) {
            Map<String, Object> map = Map.of();
            for (Map.Entry<String, FetchSearchResultProto.DocumentFieldValue> entrySet : documentFieldValue.getValueMapMap().entrySet()) {
                map.put(entrySet.getKey(), readDocumentFieldValueFromProtobuf(entrySet.getValue()));
            }
            return map;
        } else if (documentFieldValue.hasValueDate()) {
            return new Date(documentFieldValue.getValueDate());
        } else if (documentFieldValue.hasValueZonedDate() && documentFieldValue.hasValueZonedTime()) {
            return ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(documentFieldValue.getValueZonedTime()),
                ZoneId.of(documentFieldValue.getValueZonedDate())
            );
        } else if (documentFieldValue.hasValueText()) {
            return new Text(documentFieldValue.getValueText());
        } else {
            throw new IOException("Can't read generic value of type [" + documentFieldValue + "]");
        }
    }
    
}
