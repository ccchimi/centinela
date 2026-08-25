package com.centinela.fraude.infraestructura.adaptador.entrada.contrato;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Contrato de entrada del topic de transacciones.
 *
 * <p>Es un DTO del adaptador, no del dominio: existe para absorber los cambios del emisor sin
 * que el modelo se entere. Por eso las anotaciones de Bean Validation viven aquí y no en
 * {@code Transaccion}; son dos anillos distintos, uno protege la frontera con un mensaje legible
 * y otro hace que un objeto de dominio inválido sea inconstruible.
 *
 * <p>{@code ignoreUnknown = true} es deliberado: el emisor puede añadir campos a su ritmo sin
 * romper este consumidor. Lo contrario convertiría cualquier despliegue suyo en un incidente
 * nuestro.
 *
 * @param canal y {@code marca} viajan como texto libre y se traducen en
 *     {@link TraductorDeTransacciones}. Mapearlos directamente a los enums del dominio ataría el
 *     contrato de cable al nombre de una constante interna
 * @param idDispositivo opcional: los canales presenciales no lo reportan
 * @param origen opcional: las integraciones servidor a servidor no lo reportan
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TransaccionEvento(
        @NotBlank @Size(max = 64) String idTransaccion,
        @NotBlank @Size(max = 64) String idCliente,
        @NotNull @DecimalMin(value = "0.00", inclusive = false) @Digits(integer = 15, fraction = 4) BigDecimal importe,
        @NotBlank @Pattern(regexp = "[A-Za-z]{3}") String moneda,
        @NotNull @Valid InstrumentoEvento instrumento,
        @NotNull @Valid ComercioEvento comercio,
        @NotBlank String canal,
        @Valid GeolocalizacionEvento origen,
        @Size(max = 128) String idDispositivo,
        @NotNull Instant ocurridaEn) {

    /**
     * Instrumento de pago en el cable. El dominio lo modela como jerarquía sellada, que no tiene
     * representación JSON natural; aquí se aplana con un discriminador y campos opcionales, y el
     * traductor reconstruye el tipo correcto.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InstrumentoEvento(
            @NotBlank String tipo,
            String bin,
            String ultimosCuatro,
            String marca,
            Boolean presente,
            String proveedor,
            Boolean tokenizada,
            String iban,
            String bic) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ComercioEvento(
            @NotBlank @Size(max = 64) String id,
            @NotBlank @Size(max = 128) String nombre,
            @NotBlank @Pattern(regexp = "\\d{4}") String mcc,
            @NotBlank @Pattern(regexp = "[A-Za-z]{2}") String pais) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GeolocalizacionEvento(
            @NotNull Double latitud, @NotNull Double longitud, @NotBlank @Pattern(regexp = "[A-Za-z]{2}") String pais) {}
}
