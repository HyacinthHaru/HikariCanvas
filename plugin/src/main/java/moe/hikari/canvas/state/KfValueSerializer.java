package moe.hikari.canvas.state;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;

/**
 * {@link KfValue} 序列化：{@link KfValue.Num} → JSON number、{@link KfValue.Str} → JSON string、
 * {@link KfValue.FillV} → Fill 对象（委派默认序列化，含 {@code type} 字段）。
 * 与 {@link KfValueDeserializer} 严格互逆。
 */
public final class KfValueSerializer extends JsonSerializer<KfValue> {

    @Override
    public void serialize(KfValue value, JsonGenerator gen, SerializerProvider provider)
            throws IOException {
        switch (value) {
            case KfValue.Num n -> gen.writeNumber(n.value());
            case KfValue.Str s -> gen.writeString(s.value());
            case KfValue.FillV f -> provider.defaultSerializeValue(f.fill(), gen);
        }
    }
}
