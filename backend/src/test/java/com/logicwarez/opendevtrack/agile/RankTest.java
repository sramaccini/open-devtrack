/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.agile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class RankTest {

    @Test
    void theBottomGrowsByOneAndStaysShort() {
        String key = Rank.after(null);
        assertThat(key).isEqualTo(Rank.FIRST);
        for (int i = 0; i < 1000; i++) {
            String next = Rank.after(key);
            assertThat(next).hasSize(Rank.HEAD).isGreaterThan(key);
            key = next;
        }
        // The keys of the issues created before the boards, 000001 and on, are keys too.
        assertThat(Rank.after("000009")).isEqualTo("00000A");
        assertThat(Rank.before("000002")).isEqualTo("000001");
        assertThat(Rank.before("000001")).isLessThan("000001").isGreaterThan("000000");
    }

    @Test
    void aKeyBetweenTwoIsBetweenThem() {
        assertThat(Rank.between("U00000", "U00001")).isEqualTo("U00000V");
        assertThat(Rank.between("U00000", "U00002")).isEqualTo("U00001");
        assertThat(Rank.between(null, "U00000")).isLessThan("U00000");
        assertThat(Rank.between("zzzzzz", null)).isGreaterThan("zzzzzz");
        assertThatThrownBy(() -> Rank.between("U00001", "U00001")).isInstanceOf(IllegalArgumentException.class);
    }

    /** Thousands of moves to random places: the keys stay distinct, in order, valid and short. */
    @Test
    void manyMovesKeepTheOrder() {
        Random random = new Random(15);
        List<String> keys = new ArrayList<>();
        String last = null;
        for (int i = 0; i < 50; i++) {
            last = Rank.after(last);
            keys.add(last);
        }
        for (int i = 0; i < 5000; i++) {
            int from = random.nextInt(keys.size());
            keys.remove(from);
            int to = random.nextInt(keys.size() + 1);
            String low = to == 0 ? null : keys.get(to - 1);
            String high = to == keys.size() ? null : keys.get(to);
            String key = Rank.between(low, high);
            assertThat(Rank.valid(key)).isTrue();
            assertThat(key.endsWith("0") && key.length() > Rank.HEAD).isFalse();
            keys.add(to, key);
        }
        assertThat(keys).isSorted().doesNotHaveDuplicates();
        assertThat(keys.stream().mapToInt(String::length).max().orElseThrow()).isLessThan(20);
    }
}
