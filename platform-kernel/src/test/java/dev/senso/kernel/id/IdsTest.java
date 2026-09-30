package dev.senso.kernel.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdsTest {

    @Test
    void generatesVersion7Uuids() {
        for (int i = 0; i < 100; i++) {
            assertThat(Ids.newId().version()).isEqualTo(7);
        }
    }

    @Test
    void generatedIdsAreMonotonicallyIncreasing() {
        List<UUID> ids = new ArrayList<>(10_000);
        for (int i = 0; i < 10_000; i++) {
            ids.add(Ids.newId());
        }
        assertThat(ids).isSortedAccordingTo(UUID::compareTo);
        assertThat(ids).doesNotHaveDuplicates();
    }
}
