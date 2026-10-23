package it.gov.pagopa.payments.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Makes {@link StructuredLogFields} visible in the local text layout: additional SLF4J arguments
 * without message placeholders would otherwise not appear in the formatted message.
 *
 * <p>Appends readable key=value fields without turning the terminal output into a JSON record. The
 * existing layout continues to render the message, MDC and stack trace; the ECS encoder handles the
 * alternative JSON output.
 */
public class StructuredLogFieldsConverter extends ClassicConverter {

  @Override
  public String convert(ILoggingEvent event) {
    StructuredLogFields fields = StructuredLogFields.fromArguments(event.getArgumentArray());
    return fields == null ? "" : fields.toReadableText();
  }
}
