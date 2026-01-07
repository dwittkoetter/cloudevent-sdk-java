/*
 * Copyright 2018-Present The CloudEvents Authors
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package io.cloudevents.jackson;

import io.cloudevents.CloudEvent;
import io.cloudevents.CloudEventData;
import io.cloudevents.core.CloudEventUtils;
import io.cloudevents.rw.CloudEventContextReader;
import io.cloudevents.rw.CloudEventContextWriter;
import io.cloudevents.rw.CloudEventRWException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ser.std.StdSerializer;

import java.nio.charset.StandardCharsets;

/**
 * Jackson {@link com.fasterxml.jackson.databind.JsonSerializer} for {@link CloudEvent}
 */
class CloudEventSerializer extends StdSerializer<CloudEvent> {

    private final boolean forceDataBase64Serialization;
    private final boolean forceStringSerialization;

    protected CloudEventSerializer(boolean forceDataBase64Serialization, boolean forceStringSerialization) {
        super(CloudEvent.class);
        this.forceDataBase64Serialization = forceDataBase64Serialization;
        this.forceStringSerialization = forceStringSerialization;
    }

    private static class JsonContextWriter implements CloudEventContextWriter {

        private final JsonGenerator gen;
        private final SerializationContext context;

        public JsonContextWriter(JsonGenerator gen, SerializationContext context) {
            this.gen = gen;
            this.context = context;
        }

        @Override
        public CloudEventContextWriter withContextAttribute(String name, String value) throws CloudEventRWException {
            gen.writeStringProperty(name, value);
            return this;
        }

        @Override
        public CloudEventContextWriter withContextAttribute(String name, Number value) throws CloudEventRWException
        {
            // Only Integer types are supported by the specification
            if (value instanceof Integer) {
                this.withContextAttribute(name, (Integer) value);
            } else {
                // Default to string representation for other numeric values
                this.withContextAttribute(name, value.toString());
            }
            return this;
        }

        @Override
        public CloudEventContextWriter withContextAttribute(String name, Integer value) throws CloudEventRWException
        {
            gen.writeNumberProperty(name, value.intValue());
            return this;
        }

        @Override
        public CloudEventContextWriter withContextAttribute(String name, Boolean value) throws CloudEventRWException {
            gen.writeBooleanProperty(name, value);
            return this;
        }
    }

    @Override
    public void serialize(CloudEvent value, JsonGenerator gen, SerializationContext context) {
        gen.writeStartObject();
        gen.writeStringProperty("specversion", value.getSpecVersion().toString());

        // Serialize attributes
        CloudEventContextReader contextReader = CloudEventUtils.toContextReader(value);
        JsonContextWriter contextWriter = new JsonContextWriter(gen, context);
        contextReader.readContext(contextWriter);

        // Serialize data
        if (value.getData() != null) {
            CloudEventData data = value.getData();
            if (data instanceof JsonCloudEventData) {
                gen.writeName("data");
                gen.writeTree(((JsonCloudEventData) data).getNode());
            } else {
                byte[] dataBytes = data.toBytes();
                String contentType = value.getDataContentType();
                if (shouldSerializeBase64(contentType)) {
                    switch (value.getSpecVersion()) {
                        case V03:
                            gen.writeStringProperty("datacontentencoding", "base64");
                            gen.writeBinaryProperty("data", dataBytes);
                            break;
                        case V1:
                            gen.writeBinaryProperty("data_base64", dataBytes);
                            break;
                    }
                } else if (JsonFormat.dataIsJsonContentType(contentType)) {
                    // TODO really bad b/c it allocates stuff, is there another solution out there?
                    char[] dataAsString = new String(dataBytes, StandardCharsets.UTF_8).toCharArray();
                    gen.writeName("data");
                    gen.writeRawValue(dataAsString, 0, dataAsString.length);
                } else {
                    gen.writeName("data");
                    gen.writeUTF8String(dataBytes, 0, dataBytes.length);
                }
            }
        }
        gen.writeEndObject();
    }

    private boolean shouldSerializeBase64(String contentType) {
        if (JsonFormat.dataIsJsonContentType(contentType)) {
            return this.forceDataBase64Serialization;
        } else {
            return !this.forceStringSerialization;
        }
    }

}
