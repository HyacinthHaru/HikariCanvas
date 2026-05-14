package moe.hikari.canvas.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * 纯色填充。
 *
 * @param color {@code "#RRGGBB"} 或 {@code "#RRGGBBAA"}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonDeserialize(using = JsonDeserializer.None.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SolidFill(
        @JsonProperty("color") String color
) implements Fill {

    @Override
    public String type() {
        return "solid";
    }
}
