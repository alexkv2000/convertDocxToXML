package parser.model;

import jakarta.xml.bind.annotation.*;
@XmlRootElement(name = "run")
@XmlAccessorType(XmlAccessType.FIELD)
public class RunXml {
    @XmlAttribute public Boolean bold;
    @XmlAttribute public Boolean italic;
    @XmlValue public String text;
}
