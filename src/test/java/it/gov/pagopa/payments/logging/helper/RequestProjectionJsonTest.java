package it.gov.pagopa.payments.logging.helper;

import static it.gov.pagopa.payments.logging.helper.RequestProjectionJson.booleanField;
import static it.gov.pagopa.payments.logging.helper.RequestProjectionJson.dateTimeField;
import static it.gov.pagopa.payments.logging.helper.RequestProjectionJson.integerField;
import static it.gov.pagopa.payments.logging.helper.RequestProjectionJson.stringField;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import org.junit.jupiter.api.Test;

class RequestProjectionJsonTest {
  private record Source(String reference, Boolean enabled, XMLGregorianCalendar date, String extra) {}

  @Test
  void integersAreNumbersIncludingZeroAndNegativeAndNullIsOmitted() throws Exception {
    List<RequestProjectionJson.Field<Integer>> fields = List.of(integerField("count", value -> value));
    for (int value : new int[] {0, 20, -1, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
      var json = new ObjectMapper().readTree(RequestProjectionJson.serialize(value, fields));
      assertTrue(json.get("count").isInt());
      assertEquals(value, json.get("count").intValue());
    }
    assertNull(RequestProjectionJson.serialize("source",
        List.of(integerField("count", value -> null))));
  }

  @Test
  void typedFieldsShareNullOmissionAndScalarSerialization() throws Exception {
    var fields = List.of(
        stringField("reference", Source::reference),
        booleanField("enabled", Source::enabled),
        dateTimeField("date", Source::date));
    var date = DatatypeFactory.newInstance().newXMLGregorianCalendar("2026-10-15T10:00:00");
    String json = RequestProjectionJson.serialize(new Source("0001", false, date, "private"), fields);
    assertEquals(new ObjectMapper().readTree("""
        {"reference":"0001","enabled":false,"date":"2026-10-15T10:00:00"}
        """), new ObjectMapper().readTree(json));
    assertNull(RequestProjectionJson.serialize(new Source(null, null, null, "private"), fields));
  }

  @Test
  void nullSourceNeverRunsExtractorsAndEmptyAllowlistsOmitArgs() {
    AtomicInteger reads = new AtomicInteger();
    List<RequestProjectionJson.Field<Source>> fields = List.of(stringField("reference", source -> {
      reads.incrementAndGet();
      return source.reference();
    }));
    assertNull(RequestProjectionJson.serialize(null, fields));
    assertEquals(0, reads.get());
    assertNull(RequestProjectionJson.serialize(new Source("r", true, null, "private"), List.of()));
  }

  @Test
  void oneNamedDefinitionExtendsAnAllowlistWithoutChangingTheSerializer() throws Exception {
    Source source = new Source("0001", null, null, "approved-extra");
    var original = List.of(stringField("reference", Source::reference));
    var extended = List.of(
        stringField("reference", Source::reference),
        stringField("extra", Source::extra));

    assertEquals("{\"reference\":\"0001\"}", RequestProjectionJson.serialize(source, original));
    assertEquals(new ObjectMapper().readTree("{\"reference\":\"0001\",\"extra\":\"approved-extra\"}"),
        new ObjectMapper().readTree(RequestProjectionJson.serialize(source, extended)));
  }
}
