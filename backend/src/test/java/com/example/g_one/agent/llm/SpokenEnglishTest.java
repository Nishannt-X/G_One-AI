package com.example.g_one.agent.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class SpokenEnglishTest {
    @Test
    void numbersUseIndianGrouping() {
        assertThat(SpokenEnglish.number(0)).isEqualTo("zero");
        assertThat(SpokenEnglish.number(17)).isEqualTo("seventeen");
        assertThat(SpokenEnglish.number(40)).isEqualTo("forty");
        assertThat(SpokenEnglish.number(31)).isEqualTo("thirty-one");
        assertThat(SpokenEnglish.number(105)).isEqualTo("one hundred five");
        assertThat(SpokenEnglish.number(3400)).isEqualTo("three thousand four hundred");
        assertThat(SpokenEnglish.number(4500)).isEqualTo("four thousand five hundred");
        assertThat(SpokenEnglish.number(12_000)).isEqualTo("twelve thousand");
        assertThat(SpokenEnglish.number(1_50_000)).isEqualTo("one lakh fifty thousand");
        assertThat(SpokenEnglish.number(2_00_00_000)).isEqualTo("two crore");
        assertThat(SpokenEnglish.number(-3)).isEqualTo("minus three");
    }

    @Test
    void datesAreSaidAloud() {
        assertThat(SpokenEnglish.date(LocalDate.of(2026, 11, 4))).isEqualTo("the fourth of November, twenty twenty-six");
        assertThat(SpokenEnglish.date(LocalDate.of(2026, 10, 22))).isEqualTo("the twenty-second of October, twenty twenty-six");
        assertThat(SpokenEnglish.date(LocalDate.of(2026, 10, 30))).isEqualTo("the thirtieth of October, twenty twenty-six");
        assertThat(SpokenEnglish.date(LocalDate.of(2005, 1, 1))).isEqualTo("the first of January, two thousand five");
    }
}
