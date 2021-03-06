/*
 * Copyright 2016-2020 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package zipkin2.dependencies.cassandra3;

import com.datastax.spark.connector.japi.CassandraRow;
import com.datastax.spark.connector.japi.UDTValue;
import com.datastax.spark.connector.types.TypeConverter;
import java.util.Map;
import javax.annotation.Nullable;
import org.apache.spark.api.java.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Serializable;
import zipkin2.Endpoint;
import zipkin2.Span;

import static zipkin2.Span.normalizeTraceId;

enum CassandraRowToSpan implements Serializable, Function<CassandraRow, Span> {
  INSTANCE;
  final Logger log = LoggerFactory.getLogger(CassandraRowToSpan.class);

  @Override public Span call(CassandraRow row) {
    String traceId = normalizeTraceId(row.getString("trace_id"));
    if (traceId.length() == 32) traceId = traceId.substring(16);
    String spanId = row.getString("id");

    Span.Builder builder = Span.newBuilder()
      .traceId(traceId)
      .parentId(row.getString("parent_id"))
      .id(spanId)
      .timestamp(row.getLong("ts"))
      .shared(row.getBoolean("shared"));

    Map<String, String> tags = row.getMap(
      "tags", TypeConverter.StringConverter$.MODULE$, TypeConverter.StringConverter$.MODULE$);
    String error = tags.get("error");
    if (error != null) builder.putTag("error", error);
    String kind = row.getString("kind");
    if (kind != null) {
      try {
        builder.kind(Span.Kind.valueOf(kind));
      } catch (IllegalArgumentException ignored) {
        log.debug("couldn't parse kind {} in span {}/{}", kind, traceId, spanId);
      }
    }
    Endpoint localEndpoint = readEndpoint(row.getUDTValue("l_ep"));
    if (localEndpoint != null) builder.localEndpoint(localEndpoint);
    Endpoint remoteEndpoint = readEndpoint(row.getUDTValue("r_ep"));
    if (remoteEndpoint != null) builder.remoteEndpoint(remoteEndpoint);
    return builder.build();
  }

  @Nullable static Endpoint readEndpoint(UDTValue endpoint) {
    if (endpoint == null) return null;
    String serviceName = endpoint.getString("service");
    if (serviceName != null && !"".equals(serviceName)) { // not possible if written via zipkin
      return Endpoint.newBuilder().serviceName(serviceName).build();
    }
    return null;
  }
}
