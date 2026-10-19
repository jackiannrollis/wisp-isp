package com.corebalt.isp.shared.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class CsvReaderTest {

    @Test
    void readsQuotedFieldsWithCommasQuotesAndLineBreaks() {
        assertThat(CsvReader.read("a,b,c\r\n\"x, y\",\"say \"\"hi\"\"\",\"two\nlines\"\n"))
                .containsExactly(List.of("a", "b", "c"), List.of("x, y", "say \"hi\"", "two\nlines"));
    }

    @Test
    void keepsEmptyFieldsSkipsBlankLinesAndTheBom() {
        assertThat(CsvReader.read((char) 0xFEFF + "name,phone\n\nA,\n,\n"))
                .containsExactly(List.of("name", "phone"), List.of("A", ""), List.of("", ""));
    }

    @Test
    void lastLineNeedsNoLineBreak() {
        assertThat(CsvReader.read("a,b\n1,2")).containsExactly(List.of("a", "b"), List.of("1", "2"));
    }

    @Test
    void unterminatedQuoteIsAnError() {
        assertThatThrownBy(() -> CsvReader.read("a\n\"open")).isInstanceOf(IllegalArgumentException.class);
    }
}
