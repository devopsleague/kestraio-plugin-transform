package io.kestra.plugin.transform.jsonata;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.FileSerde;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@KestraTest
class TransformItemsTest {

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void shouldGetOutputForValidExprReturningStringForFromURI() throws Exception {
        // Given
        RunContext runContext = runContextFactory.of();
        final Path ouputFilePath = runContext.workingDir().createTempFile(".ion");
        try (final OutputStream os = Files.newOutputStream(ouputFilePath)) {
            FileSerde.writeAll(os, Flux.just(
                new ObjectMapper().readValue(Features.DATASET_ACCOUNT_ORDER_JSON, Map.class),
                new ObjectMapper().readValue(Features.DATASET_ACCOUNT_ORDER_JSON, Map.class))).block();
            os.flush();
        }
        URI uri = runContext.storage().putFile(ouputFilePath.toFile());

        TransformItems task = TransformItems.builder()
            .from(uri.toString())
            .expr(Features.DATASET_ACCOUNT_ORDER_EXPR)
            .build();

        // When
        TransformItems.Output output = task.run(runContext);

        // Then
        Assertions.assertNotNull(output);
        Assertions.assertEquals(2, output.getProcessedItemsTotal());

        InputStream is = runContext.storage().getFile(output.getUri());
        String transformationResult = FileSerde.readAll(is, new TypeReference<String>() {
        }).blockLast();

        Assertions.assertEquals(Features.DATASET_ACCOUNT_ORDER_EXPR_RESULT, transformationResult);
    }


}