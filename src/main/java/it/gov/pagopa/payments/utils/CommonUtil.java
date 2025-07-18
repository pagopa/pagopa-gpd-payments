package it.gov.pagopa.payments.utils;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Objects;
import java.util.Optional;
import javax.servlet.http.HttpServletRequest;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.transform.stax.StAXSource;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;

import lombok.experimental.UtilityClass;
import org.springframework.ws.transport.context.TransportContextHolder;
import org.springframework.ws.transport.http.HttpServletConnection;
import org.xml.sax.SAXException;

@UtilityClass
public class CommonUtil {

  public static final String SERVICE_TYPE_HEADER = "X-Client-Service-Type";

  /**
   * @param xml file XML to validate
   * @param xsdUrl url of XSD
   * @throws SAXException if XML is not valid
   * @throws IOException if XSD schema not found
   * @throws XMLStreamException error during read XML
   */
  public static void syntacticValidationXml(byte[] xml, File xsdUrl)
      throws SAXException, IOException, XMLStreamException {
    SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
    // to be compliant, prohibit the use of all protocols by external entities:
    factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

    javax.xml.validation.Schema schema = factory.newSchema(xsdUrl);
    Validator validator = schema.newValidator();

    XMLInputFactory xmlInputFactory = XMLInputFactory.newInstance();
    // to be compliant, completely disable DOCTYPE declaration:
    xmlInputFactory.setProperty(XMLInputFactory.SUPPORT_DTD, false);

    XMLStreamReader xmlStreamReader =
        xmlInputFactory.createXMLStreamReader(new ByteArrayInputStream(xml));
    StAXSource source = new StAXSource(xmlStreamReader);
    validator.validate(source);
  }

  public static GregorianCalendar convertToGregorianCalendar(LocalDateTime dateToConvert) {
    Date date = Date.from(dateToConvert.atZone(ZoneId.systemDefault()).toInstant());
    GregorianCalendar gregorianCalendar = new GregorianCalendar();
    gregorianCalendar.setTime(date);

    return gregorianCalendar;
  }

  public static String sanitizeInput(String input) {
    return input != null ? input.replace("\n", "").replaceAll("[^a-zA-Z0-9_\\-+/]", "") : "";
  }

  /**
   * @param value value to deNullify.
   * @return return empty string if value is null
   */
  public static String deNull(Object value) {
    return Optional.ofNullable(value).orElse("").toString();
  }

  /**
   * The function permits to extract the <i>serviceType</i> value from header
   * <b>X-Caller-Service-Type</b> in request. If no header is found with that
   * name, the value <i>"GPD"</i> is returned as default.
   *
   * @return the service type value
   */
  public static String getServiceType() {
    HttpServletRequest servletRequest = ((HttpServletConnection)
            TransportContextHolder.getTransportContext().getConnection())
            .getHttpServletRequest();
    String serviceType = servletRequest.getHeader(SERVICE_TYPE_HEADER);
    return Objects.requireNonNullElse(serviceType, "GPD");
  }

  public static long getFeeInCent(BigDecimal fee) {
    long feeInCent = 0;
    if (null != fee) {
      feeInCent = fee.multiply(BigDecimal.valueOf(100)).longValue();
    }
    return feeInCent;
  }
}
