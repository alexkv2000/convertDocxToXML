package parser.model;

import jakarta.xml.bind.annotation.*;
import java.util.ArrayList;
import java.util.List;
@XmlRootElement(name = "document")
@XmlAccessorType(XmlAccessType.FIELD)
public class DocumentXml {
    @XmlAttribute public String fileName;
    @XmlAttribute public String processedAt;
    @XmlElementRef public List<Object> body = new ArrayList<>();
}