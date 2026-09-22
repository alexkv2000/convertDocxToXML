package kvo.convertxml.parser.model;

import jakarta.xml.bind.annotation.*;
import java.util.ArrayList;
import java.util.List;
@XmlAccessorType(XmlAccessType.FIELD)
public class TableRowXml {
    @XmlElement(name = "cell")
    public List<TableCellXml> cells = new ArrayList<>();
}