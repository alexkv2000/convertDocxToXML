package kvo.convertxml.parser.model;

import jakarta.xml.bind.annotation.*;
import java.util.ArrayList;
import java.util.List;
@XmlRootElement(name = "table")
@XmlAccessorType(XmlAccessType.FIELD)
public class TableXml {
    @XmlElement(name = "row")
    public List<TableRowXml> rows = new ArrayList<>();
}