package kvo.convertxml.parser.model;

import jakarta.xml.bind.annotation.*;
@XmlAccessorType(XmlAccessType.FIELD)
public class RunXml {
    @XmlAttribute
    public Boolean bold;
    @XmlAttribute
    public Boolean italic;
    @XmlValue
    public String text;
}
