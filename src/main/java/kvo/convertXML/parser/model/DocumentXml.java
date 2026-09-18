package kvo.convertXML.parser.model;

import jakarta.xml.bind.annotation.*;
import java.util.ArrayList;
import java.util.List;
@XmlRootElement(name = "document")
@XmlAccessorType(XmlAccessType.FIELD)
public class DocumentXml {
    @XmlAttribute(name = "fileName")
    public String fileName;
    @XmlAttribute(name = "processedAt")
    public String processedAt;
    @XmlAnyElement(lax = true)
    public List<Object> body = new ArrayList<>();
}