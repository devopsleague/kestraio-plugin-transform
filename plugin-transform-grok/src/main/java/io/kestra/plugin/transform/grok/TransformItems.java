package io.kestra.plugin.transform.grok;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.tasks.Output;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.serializers.JacksonMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import reactor.core.publisher.Flux;

import java.io.BufferedWriter;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static io.kestra.core.utils.Rethrow.throwFunction;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Parse arbitrary text and structure it using Grok expressions.",
    description = """
        The `TransformItems` task is similar to the famous Logstash Grok filter from the ELK stack.
        It is particularly useful for transforming unstructured data such as logs into a structured, indexable, and queryable data structure.
                
        The `TransformItems` ships with all the default patterns as defined You can find them here: https://github.com/kestra-io/plugin-transform/tree/main/plugin-transforms-grok/src/main/resources/patterns.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Consume, parse, and structure logs events from Kafka topic.",
            full = true,
            code = """
                id: grok
                namespace: myteam

                tasks:
                - id: grok
                  type: io.kestra.plugin.transform.grok.TransformItems
                  pattern: "%{TIMESTAMP_ISO8601:logdate} %{LOGLEVEL:loglevel} %{GREEDYDATA:message}"
                  from: "{{ trigger.uri }}"
                          
                triggers:
                - id: trigger
                  type: io.kestra.plugin.kafka.Trigger
                  topic: test_kestra
                  properties:
                    bootstrap.servers: localhost:9092
                  serdeProperties:
                    schema.registry.url: http://localhost:8085
                    keyDeserializer: STRING
                    valueDeserializer: STRING
                  groupId: kafkaConsumerGroupId
                  interval: PT30S
                  maxRecords: 5
                """
        )
    }
)
public class TransformItems extends Transform implements GrokInterface, RunnableTask<Output> {

    private static final ObjectMapper ION_OBJECT_MAPPER = JacksonMapper.ofIon();

    @Schema(
        title = "The file to be transformed.",
        description = "Must be a `kestra://` internal storage URI."
    )
    @PluginProperty(dynamic = true)
    @NotNull
    private String from;

    /**
     * {@inheritDoc}
     **/
    @Override
    public Output run(RunContext runContext) throws Exception {
        init(runContext);

        String from = runContext.render(this.from);

        URI objectURI = new URI(from);
        try (InputStream is = runContext.storage().getFile(objectURI);) {
            Flux<String> flux = FileSerde.readAll(is, new TypeReference<String>() {
            });
            final Path ouputFilePath = runContext.workingDir().createTempFile(".ion");
            try (final BufferedWriter writer = Files.newBufferedWriter(ouputFilePath)) {
                Long processedItemsTotal = flux.map(throwFunction(data -> {
                        Map<String, Object> captured = matches(data.getBytes(StandardCharsets.UTF_8));
                        writer.write(ION_OBJECT_MAPPER.writeValueAsString(captured));
                        writer.newLine();
                        return 1L;
                    }))
                    .reduce(Long::sum)
                    .block();

                writer.flush();

                URI uri = runContext.storage().putFile(ouputFilePath.toFile());
                return Output
                    .builder()
                    .uri(uri)
                    .processedItemsTotal(processedItemsTotal)
                    .build();
            } finally {
                Files.deleteIfExists(ouputFilePath); // ensure temp file is deleted in case of error
            }
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "The transformed file URI."
        )
        private final URI uri;

        @Schema(
            title = "The total number of items that was processed by the task."
        )
        private final Long processedItemsTotal;
    }
}
