package kvo.convertXML.parser.model;

import jakarta.xml.bind.annotation.*;
import java.util.ArrayList;
import java.util.List;
@XmlAccessorType(XmlAccessType.FIELD)
public class TableCellXml {
    @XmlAttribute
    public Integer gridSpan;
    @XmlAttribute
    public String vMerge;
    @XmlElement(name = "paragraph")
    public List<ParagraphXml> paragraphs = new ArrayList<>();
}