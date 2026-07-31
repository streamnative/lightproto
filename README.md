# LightProto

High-performance Protocol Buffers code generator for Java, optimized for serialization and deserialization speed.

## Features

- **Fastest Java Protobuf SerDe** — Unsafe-based serialization bypasses Netty ByteBuf boundary checks
- **100% wire-compatible** with proto2 and proto3 definitions
- **Zero-copy deserialization** using Netty `ByteBuf` (direct and heap memory)
- **Zero heap allocations** — reusable mutable objects, no Builder pattern overhead
- **Lazy string/bytes deserialization** — decoded only on access
- **Optimized string handling** — single-copy ASCII fast path via `sun.misc.Unsafe`
- **Protobuf-compatible JSON serialization and deserialization** — `toJson()` / `parseFromJson()` methods
- **Protobuf TextFormat (de)serialization** — opt-in `toTextFormat()` / `parseFromTextFormat()` for compatibility with `com.google.protobuf.TextFormat`
- **No runtime dependencies** — generated code is self-contained
- **Maven and Gradle plugins** for seamless build integration

## Usage

### Maven

Add the Maven plugin to your `pom.xml`:

```xml
<plugin>
    <groupId>io.streamnative.lightproto</groupId>
    <artifactId>lightproto-maven-plugin</artifactId>
    <version>0.8.0</version>
    <executions>
        <execution>
            <goals>
                <goal>generate</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

Place `.proto` files in `src/main/proto/` and LightProto will generate Java classes during the
`generate-sources` phase.

### Gradle

Add the plugin to your `build.gradle`:

```groovy
plugins {
    id 'io.streamnative.lightproto' version '0.8.0'
}
```

Or using the `buildscript` block:

```groovy
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath 'io.streamnative.lightproto:lightproto-gradle-plugin:0.8.0'
    }
}

apply plugin: 'io.streamnative.lightproto'
```

Place `.proto` files in `src/main/proto/` and LightProto will generate Java classes automatically
before compilation. Optional configuration:

```groovy
lightproto {
    classPrefix = ''           // prefix for generated class names
    singleOuterClass = false   // wrap all messages in a single outer class
    generateTextFormat = false // also generate protobuf TextFormat (de)serialization methods
    protocVersion = '4.34.0'   // protoc compiler version
    // protocPath = '/usr/local/bin/protoc'  // use a local protoc binary
}
```

For Maven, the same options are configured under `<configuration>` on the plugin:

```xml
<plugin>
    <groupId>io.streamnative.lightproto</groupId>
    <artifactId>lightproto-maven-plugin</artifactId>
    <configuration>
        <generateTextFormat>true</generateTextFormat>
    </configuration>
    <!-- ... -->
</plugin>
```

### API Example

LightProto generates mutable, reusable objects instead of the Builder pattern used by Google Protobuf:

```java
// Create and populate
MessageMetadata md = new MessageMetadata();
md.setProducerName("producer-1")
  .setSequenceId(12345)
  .setPublishTime(System.currentTimeMillis());
md.addProperty().setKey("key1").setValue("value1");

// Serialize to ByteBuf
ByteBuf buf = PooledByteBufAllocator.DEFAULT.buffer();
md.writeTo(buf);

// Deserialize from ByteBuf (zero-copy, lazy strings)
MessageMetadata parsed = new MessageMetadata();
parsed.parseFrom(buf, buf.readableBytes());

// Reuse the object for the next message
md.clear();
md.setProducerName("producer-2")...
```

### JSON Serialization and Deserialization

Every generated message has built-in JSON support compatible with
protobuf's [`JsonFormat`](https://protobuf.dev/programming-guides/json/):

```java
// Serialize to JSON string
String json = md.toJson();
// {"producerName":"producer-1","sequenceId":"12345","publishTime":"1711234567890",...}

// Or write directly to a ByteBuf for zero-copy networking
ByteBuf jsonBuf = PooledByteBufAllocator.DEFAULT.buffer();
md.writeJsonTo(jsonBuf);

// Deserialize from JSON string
MessageMetadata parsed = new MessageMetadata();
parsed.parseFromJson(json);

// Or from a ByteBuf (avoids String allocation)
parsed.parseFromJson(jsonBuf);

// Or from a byte array
parsed.parseFromJson(jsonBytes);
```

The JSON encoding follows protobuf conventions: lowerCamelCase field names, int64 values quoted
as strings, enum values as names, and bytes fields as base64. Unknown fields are silently ignored
during parsing, ensuring forward compatibility.

### Protobuf TextFormat (opt-in)

Set `generateTextFormat = true` (Gradle) or `<generateTextFormat>true</generateTextFormat>`
(Maven) to also emit TextFormat (de)serialization on every message. The output is compatible
with `com.google.protobuf.TextFormat.printer()` and `TextFormat.merge()` for backward
compatibility with existing TextFormat data:

```java
// Serialize to a multi-line, indented TextFormat string
String text = md.toTextFormat();
// producer_name: "producer-1"
// sequence_id: 12345
// publish_time: 1711234567890
// property {
//   key: "key1"
//   value: "value1"
// }

// Or write to a StringBuilder
StringBuilder sb = new StringBuilder();
md.writeTextFormatTo(sb);

// Deserialize from a String, byte[], or ByteBuf
MessageMetadata parsed = new MessageMetadata();
parsed.parseFromTextFormat(text);
```

Differences from JSON: field names are the original proto snake_case, `int64` values are
**not** quoted, enum values are emitted as bare identifiers, and bytes are written as
quoted strings with C-style escapes. The parser tolerates `# … ` comments, single- or
double-quoted strings, angle-bracket sub-messages (`field <…>`), `[v1, v2]` array syntax for
repeated fields, and ignores unknown fields.

### gRPC Integration

LightProto generates `*Grpc.java` service stubs directly from `service` definitions in `.proto`
files — no extra protoc plugin needed. The generated code follows the same structure as
`protoc-gen-grpc-java` but uses LightProto messages, so you get gRPC with zero-copy
serialization and no Google Protobuf runtime dependency.

Add the gRPC dependency to your project:

```xml
<dependency>
    <groupId>io.grpc</groupId>
    <artifactId>grpc-stub</artifactId>
    <version>1.68.0</version>
</dependency>
```

Define a service in your `.proto` file:

```protobuf
syntax = "proto3";
package myapp;

message HelloRequest  { string name = 1; }
message HelloResponse { string greeting = 1; }

service Greeter {
    rpc SayHello (HelloRequest) returns (HelloResponse);
    rpc SayHelloStream (HelloRequest) returns (stream HelloResponse);
}
```

Implement the service by extending the generated `ImplBase`:

```java
class GreeterImpl extends GreeterGrpc.GreeterImplBase {
    @Override
    public void sayHello(HelloRequest request, StreamObserver<HelloResponse> responseObserver) {
        HelloResponse response = new HelloResponse();
        response.setGreeting("Hello, " + request.getName() + "!");
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
```

Create a client using the generated stubs:

```java
// Blocking stub (unary and server-streaming)
GreeterGrpc.GreeterBlockingStub blocking = GreeterGrpc.newBlockingStub(channel);
HelloRequest request = new HelloRequest();
request.setName("World");
HelloResponse response = blocking.sayHello(request);

// Async stub (all method types including client/bidi streaming)
GreeterGrpc.GreeterStub async = GreeterGrpc.newStub(channel);
async.sayHelloStream(request, new StreamObserver<HelloResponse>() { ... });
```

The generated stubs support all four gRPC method types: unary, server streaming,
client streaming, and bidirectional streaming.

## Supported Features

| Feature | proto2 | proto3 |
|:---|:---:|:---:|
| Scalar fields (int32, int64, uint32, uint64, sint32, sint64, fixed32, fixed64, sfixed32, sfixed64, float, double, bool) | ✅ | ✅ |
| String fields | ✅ | ✅ |
| Bytes fields | ✅ | ✅ |
| Enum fields | ✅ | ✅ |
| Nested messages | ✅ | ✅ |
| `optional` fields (explicit presence) | ✅ | ✅ |
| `required` fields | ✅ | — |
| Implicit presence (no `has*()`, skip defaults) | — | ✅ |
| `oneof` | ✅ | ✅ |
| `repeated` fields | ✅ | ✅ |
| `repeated` packed encoding | ✅ | ✅ (default) |
| `map<K, V>` fields | ✅ | ✅ |
| Nested enum / message definitions | ✅ | ✅ |
| Default values | ✅ | — |
| Multiple `.proto` files / `import` | ✅ | ✅ |
| `service` / RPC definitions (gRPC stubs) | ✅ | ✅ |
| JSON serialization and deserialization | ✅ | ✅ |
| TextFormat serialization and deserialization (opt-in) | ✅ | ✅ |
| Extensions | ❌ | — |
| `Any`, `Timestamp`, well-known types | ❌ | ❌ |
| `group` (deprecated) | ❌ | — |

## Performance

### Throughput Comparison (ops/&mu;s, higher is better)

| Benchmark | Google Protobuf | LightProto | Speedup |
|:---|---:|---:|---:|
| **AddressBook** (nested messages, strings) | | | |
| &emsp;Serialize | 6.5 | 24.7 | **3.8x** |
| &emsp;Fill + Serialize | 2.3 | 15.1 | **6.6x** |
| &emsp;Deserialize | 3.6 | 26.1 | **7.2x** |
| **Simple** (tiny message: string + nested numerics) | | | |
| &emsp;Serialize | 28.2 | 163.0 | **5.8x** |
| &emsp;Deserialize | 16.1 | 82.5 | **5.1x** |
| &emsp;Deserialize + read string | 16.1 | 50.7 | **3.2x** |
| **Pulsar MessageMetadata** (strings, properties, batch fields) | | | |
| &emsp;Serialize | 2.9 | 13.9 | **4.8x** |
| &emsp;Deserialize | 4.1 | 23.2 | **5.6x** |
| **Pulsar BaseCommand+Send** (nested message, numerics) | | | |
| &emsp;Serialize | 16.0 | 41.3 | **2.6x** |
| &emsp;Deserialize | 12.5 | 46.4 | **3.7x** |

### Speedup Chart

```
                        Speedup over Google Protobuf (x times faster)
                        1x    2x    3x    4x    5x    6x    7x    8x
                        |     |     |     |     |     |     |     |
AddressBook Ser         |███████████████████████                    3.8x
AddressBook Fill+Ser    |████████████████████████████████████████   6.6x
AddressBook Deser       |███████████████████████████████████████████ 7.2x
                        |     |     |     |     |     |     |     |
Simple Ser              |███████████████████████████████████        5.8x
Simple Deser            |███████████████████████████████            5.1x
Simple Deser+ReadStr    |███████████████████                        3.2x
                        |     |     |     |     |     |     |     |
Pulsar MD Ser           |█████████████████████████████              4.8x
Pulsar MD Deser         |██████████████████████████████████         5.6x
                        |     |     |     |     |     |     |     |
Pulsar Cmd Ser          |████████████████                           2.6x
Pulsar Cmd Deser        |██████████████████████                     3.7x
                        |     |     |     |     |     |     |     |
```

### Raw JMH Output

<details>
<summary>Click to expand full benchmark output</summary>

```
Benchmark                                                    Mode  Cnt    Score    Error   Units

-- AddressBook (nested messages with strings) --
ProtoBenchmark.protobufSerialize                            thrpt    3    6.493 ±  0.164  ops/us
ProtoBenchmark.protobufFillAndSerialize                     thrpt    3    2.289 ±  0.635  ops/us
ProtoBenchmark.protobufDeserialize                          thrpt    3    3.634 ±  0.817  ops/us
ProtoBenchmark.lightProtoSerialize                          thrpt    3   24.671 ±  1.839  ops/us
ProtoBenchmark.lightProtoFillAndSerialize                   thrpt    3   15.052 ±  4.058  ops/us
ProtoBenchmark.lightProtoDeserialize                        thrpt    3   26.062 ±  6.488  ops/us

-- Simple (tiny message: string + nested numerics) --
SimpleBenchmark.protobufSerialize                           thrpt    3   28.187 ±  2.962  ops/us
SimpleBenchmark.protobufDeserialize                         thrpt    3   16.055 ±  5.500  ops/us
SimpleBenchmark.lightProtoSerialize                         thrpt    3  163.043 ±  1.449  ops/us
SimpleBenchmark.lightProtoDeserialize                       thrpt    3   82.527 ± 57.963  ops/us
SimpleBenchmark.lightProtoDeserializeReadString             thrpt    3   50.745 ± 11.938  ops/us

-- Pulsar MessageMetadata (strings, repeated properties, batch fields) --
PulsarApiBenchmark.protobufSerializeMessageMetadata         thrpt    3    2.883 ±  0.837  ops/us
PulsarApiBenchmark.protobufDeserializeMessageMetadata       thrpt    3    4.134 ±  0.583  ops/us
PulsarApiBenchmark.lightProtoSerializeMessageMetadata       thrpt    3   13.927 ±  6.783  ops/us
PulsarApiBenchmark.lightProtoDeserializeMessageMetadata     thrpt    3   23.244 ±  0.452  ops/us

-- Pulsar BaseCommand + CommandSend (nested message, mostly numerics) --
PulsarApiBenchmark.protobufSerializeBaseCommand             thrpt    3   16.042 ±  3.728  ops/us
PulsarApiBenchmark.protobufDeserializeBaseCommand           thrpt    3   12.531 ±  1.683  ops/us
PulsarApiBenchmark.lightProtoSerializeBaseCommand           thrpt    3   41.282 ±  3.299  ops/us
PulsarApiBenchmark.lightProtoDeserializeBaseCommand         thrpt    3   46.423 ± 13.136  ops/us
```

</details>

### Running Benchmarks

```bash
mvn -B install
java -jar benchmark/target/benchmarks.jar
```

## License

Licensed under the Apache License, Version 2.0.
