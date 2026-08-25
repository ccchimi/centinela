package com.centinela.fraude.infraestructura.adaptador.salida.dynamodb;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Conversión entre tipos Java y {@link AttributeValue}.
 *
 * <p>Existe porque el mapeo a DynamoDB se hace a mano y no con el enhanced client. Ver ADR-0002
 * en el README: el mapeo por bean necesita reflexión y un conversor por cada value object, y
 * aquí todo el modelo son records con {@code Dinero}, {@code Optional} y conjuntos.
 *
 * <h2>Dos decisiones de codificación</h2>
 *
 * <ul>
 *   <li><strong>Los instantes van como número (epoch millis), no como texto ISO.</strong>
 *       {@code Instant.toString()} omite los segundos cuando son cero
 *       ({@code 2026-08-24T12:00Z}) y añade nanos cuando los hay, así que dos instantes
 *       comparables producen cadenas que <em>no</em> ordenan igual. La escritura condicionada
 *       del perfil compara ese campo, y con texto compararía mal en silencio.
 *   <li><strong>Los conjuntos van como lista (L), no como string set (SS).</strong> DynamoDB
 *       rechaza un SS vacío, y un cliente sin países habituales es un caso normal.
 * </ul>
 */
final class Atributos {

    private Atributos() {}

    // ------------------------------------------------------------------ escritura

    static AttributeValue texto(String valor) {
        return AttributeValue.fromS(valor);
    }

    static AttributeValue numero(long valor) {
        return AttributeValue.fromN(Long.toString(valor));
    }

    static AttributeValue numero(BigDecimal valor) {
        return AttributeValue.fromN(valor.toPlainString());
    }

    static AttributeValue numero(double valor) {
        return AttributeValue.fromN(Double.toString(valor));
    }

    static AttributeValue momento(Instant valor) {
        return numero(valor.toEpochMilli());
    }

    static AttributeValue mapa(Map<String, AttributeValue> valor) {
        return AttributeValue.fromM(valor);
    }

    static <T> AttributeValue lista(Collection<T> valores, Function<T, AttributeValue> conversor) {
        return AttributeValue.fromL(valores.stream().map(conversor).toList());
    }

    static AttributeValue listaDeTextos(Collection<String> valores) {
        return lista(valores, Atributos::texto);
    }

    // ------------------------------------------------------------------ lectura

    static String texto(Map<String, AttributeValue> item, String campo) {
        return leer(item, campo).map(AttributeValue::s).orElseThrow(() -> falta(campo));
    }

    static long numeroLargo(Map<String, AttributeValue> item, String campo) {
        return leer(item, campo).map(AttributeValue::n).map(Long::parseLong).orElseThrow(() -> falta(campo));
    }

    static int numeroEntero(Map<String, AttributeValue> item, String campo) {
        return Math.toIntExact(numeroLargo(item, campo));
    }

    static double numeroDecimal(Map<String, AttributeValue> item, String campo) {
        return leer(item, campo).map(AttributeValue::n).map(Double::parseDouble).orElseThrow(() -> falta(campo));
    }

    static BigDecimal numeroExacto(Map<String, AttributeValue> item, String campo) {
        return leer(item, campo).map(AttributeValue::n).map(BigDecimal::new).orElseThrow(() -> falta(campo));
    }

    static Instant momento(Map<String, AttributeValue> item, String campo) {
        return Instant.ofEpochMilli(numeroLargo(item, campo));
    }

    static Optional<Instant> momentoOpcional(Map<String, AttributeValue> item, String campo) {
        return leer(item, campo).map(AttributeValue::n).map(Long::parseLong).map(Instant::ofEpochMilli);
    }

    static Map<String, AttributeValue> mapa(Map<String, AttributeValue> item, String campo) {
        return leer(item, campo).map(AttributeValue::m).orElseThrow(() -> falta(campo));
    }

    static Optional<Map<String, AttributeValue>> mapaOpcional(Map<String, AttributeValue> item, String campo) {
        return leer(item, campo).map(AttributeValue::m);
    }

    static <T> List<T> lista(
            Map<String, AttributeValue> item, String campo, Function<AttributeValue, T> conversor) {
        return leer(item, campo).map(AttributeValue::l).orElse(List.of()).stream()
                .map(conversor)
                .toList();
    }

    static List<String> listaDeTextos(Map<String, AttributeValue> item, String campo) {
        return lista(item, campo, AttributeValue::s);
    }

    /**
     * Un atributo ausente y un atributo con NULL son lo mismo para nosotros: dato que no está.
     * DynamoDB los distingue y esa distinción no significa nada en este dominio.
     */
    private static Optional<AttributeValue> leer(Map<String, AttributeValue> item, String campo) {
        AttributeValue valor = item.get(campo);
        return valor == null || Boolean.TRUE.equals(valor.nul()) ? Optional.empty() : Optional.of(valor);
    }

    private static ItemCorruptoException falta(String campo) {
        return new ItemCorruptoException("falta el atributo obligatorio '%s'".formatted(campo));
    }
}
