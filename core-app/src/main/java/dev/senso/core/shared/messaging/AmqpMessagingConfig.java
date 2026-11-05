package dev.senso.core.shared.messaging;

import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON-конвертер AMQP для всех листенеров {@code core}: сообщения из {@code senso.validated} — это
 * {@code ValidatedMessageV1} в JSON (ingest публикует их тем же конвертером Jackson 3). Тип берётся из параметра
 * метода {@code @RabbitListener}, а не из заголовка {@code __TypeId__}.
 *
 * <p>Boot подхватывает бин и для фабрики листенеров, и для {@code RabbitTemplate}.
 */
@Configuration(proxyBeanMethods = false)
public class AmqpMessagingConfig {

    @Bean
    MessageConverter amqpMessageConverter(JsonMapper jsonMapper) {
        return new JacksonJsonMessageConverter(jsonMapper);
    }
}
