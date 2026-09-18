package kvo.convertXML.parser.model;

import jakarta.xml.bind.annotation.*;
import java.util.ArrayList;
import java.util.List;
@XmlRootElement(name = "paragraph")
@XmlAccessorType(XmlAccessType.FIELD)
public class ParagraphXml {
    @XmlAttribute
    public String style;
    @XmlAttribute
    public String alignment;
    @XmlElement(name = "run")
    public List<RunXml> runs = new ArrayList<>();
}