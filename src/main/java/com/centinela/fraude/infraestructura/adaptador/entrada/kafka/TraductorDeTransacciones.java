package com.centinela.fraude.infraestructura.adaptador.entrada.kafka;

import com.centinela.fraude.dominio.excepcion.ErrorDeDominio;
import com.centinela.fraude.dominio.modelo.Transaccion;
import com.centinela.fraude.dominio.modelo.vo.Canal;
import com.centinela.fraude.dominio.modelo.vo.Comercio;
import com.centinela.fraude.dominio.modelo.vo.Dinero;
import com.centinela.fraude.dominio.modelo.vo.Geolocalizacion;
import com.centinela.fraude.dominio.modelo.vo.IdCliente;
import com.centinela.fraude.dominio.modelo.vo.IdDispositivo;
import com.centinela.fraude.dominio.modelo.vo.IdTransaccion;
import com.centinela.fraude.dominio.modelo.vo.InstrumentoDePago;
import com.centinela.fraude.dominio.modelo.vo.MarcaDeTarjeta;
import com.centinela.fraude.infraestructura.adaptador.entrada.kafka.TransaccionEvento.ComercioEvento;
import com.centinela.fraude.infraestructura.adaptador.entrada.kafka.TransaccionEvento.GeolocalizacionEvento;
import com.centinela.fraude.infraestructura.adaptador.entrada.kafka.TransaccionEvento.InstrumentoEvento;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Arrays;
import java.util.Currency;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Traduce el evento del cable a la transacción del dominio.
 *
 * <p>Es la frontera del hexágono en la dirección de entrada, y hace dos cosas en este orden:
 * valida el DTO con Bean Validation y luego construye los objetos de dominio, que validan otra
 * vez sus propias invariantes. No es duplicación: la primera pasada da un mensaje de error
 * legible que apunta al campo del emisor, la segunda garantiza que no exista una
 * {@code Transaccion} inválida aunque alguien invoque este código de otra forma.
 *
 * <p>Todo lo que falle sale como {@link EventoInvalidoException}, que el manejador de errores
 * trata como no reintentable y manda a la cola de descartes.
 */
@Component
public class TraductorDeTransacciones {

    private static final String TARJETA = "TARJETA";
    private static final String BILLETERA = "BILLETERA";
    private static final String TRANSFERENCIA = "TRANSFERENCIA";

    private final Validator validador;

    public TraductorDeTransacciones(Validator validador) {
        this.validador = validador;
    }

    public Transaccion aDominio(TransaccionEvento evento) {
        if (evento == null) {
            throw new EventoInvalidoException("El mensaje no traía carga útil");
        }
        exigirQueSeaValido(evento);

        try {
            return new Transaccion(
                    IdTransaccion.de(evento.idTransaccion()),
                    IdCliente.de(evento.idCliente()),
                    Dinero.de(evento.importe(), moneda(evento.moneda())),
                    aInstrumento(evento.instrumento(), evento.idDispositivo()),
                    aComercio(evento.comercio()),
                    aCanal(evento.canal()),
                    Optional.ofNullable(evento.origen()).map(TraductorDeTransacciones::aGeolocalizacion),
                    Optional.ofNullable(evento.idDispositivo()).map(IdDispositivo::de),
                    evento.ocurridaEn());
        } catch (ErrorDeDominio e) {
            throw new EventoInvalidoException(
                    "La transacción %s no cumple las reglas del dominio: %s".formatted(evento.idTransaccion(), e.getMessage()),
                    e);
        }
    }

    private void exigirQueSeaValido(TransaccionEvento evento) {
        Set<ConstraintViolation<TransaccionEvento>> violaciones = validador.validate(evento);
        if (!violaciones.isEmpty()) {
            String detalle = violaciones.stream()
                    .map(v -> "%s %s".formatted(v.getPropertyPath(), v.getMessage()))
                    .sorted()
                    .collect(Collectors.joining("; "));
            throw new EventoInvalidoException("Evento de transacción inválido: " + detalle);
        }
    }

    /**
     * Reconstruye la jerarquía sellada a partir del discriminador del cable.
     *
     * <p>El {@code switch} tiene {@code default} porque el tipo llega como texto de un sistema
     * externo: aquí la exhaustividad no la puede garantizar el compilador, y lo correcto es
     * rechazar lo desconocido en vez de adivinar.
     *
     * @param idDispositivo huella de la transacción; una billetera digital vive necesariamente
     *     en el dispositivo que ordena el pago, así que el dominio la exige y el cable no la
     *     repite dentro del instrumento
     */
    private static InstrumentoDePago aInstrumento(InstrumentoEvento instrumento, String idDispositivo) {
        String tipo = instrumento.tipo().strip().toUpperCase(Locale.ROOT);
        return switch (tipo) {
            case TARJETA -> new InstrumentoDePago.Tarjeta(
                    exigido(instrumento.bin(), "instrumento.bin"),
                    exigido(instrumento.ultimosCuatro(), "instrumento.ultimosCuatro"),
                    aMarca(instrumento.marca()),
                    Boolean.TRUE.equals(instrumento.presente()));
            case BILLETERA -> new InstrumentoDePago.BilleteraDigital(
                    exigido(instrumento.proveedor(), "instrumento.proveedor"),
                    IdDispositivo.de(exigido(idDispositivo, "idDispositivo")),
                    Boolean.TRUE.equals(instrumento.tokenizada()));
            case TRANSFERENCIA -> new InstrumentoDePago.TransferenciaBancaria(
                    exigido(instrumento.iban(), "instrumento.iban"), exigido(instrumento.bic(), "instrumento.bic"));
            default -> throw new EventoInvalidoException(
                    "Tipo de instrumento desconocido: '%s'. Admitidos: %s, %s, %s"
                            .formatted(instrumento.tipo(), TARJETA, BILLETERA, TRANSFERENCIA));
        };
    }

    private static MarcaDeTarjeta aMarca(String marca) {
        if (marca == null || marca.isBlank()) {
            return MarcaDeTarjeta.OTRA;
        }
        try {
            return MarcaDeTarjeta.valueOf(marca.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // Una marca nueva no debe tumbar la evaluación: el resto de señales sigue valiendo.
            return MarcaDeTarjeta.OTRA;
        }
    }

    private static Canal aCanal(String canal) {
        try {
            return Canal.valueOf(canal.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new EventoInvalidoException(
                    "Canal desconocido: '%s'. Admitidos: %s".formatted(canal, Arrays.toString(Canal.values())), e);
        }
    }

    private static Comercio aComercio(ComercioEvento comercio) {
        return Comercio.de(comercio.id(), comercio.nombre(), comercio.mcc(), comercio.pais());
    }

    private static Geolocalizacion aGeolocalizacion(GeolocalizacionEvento origen) {
        return Geolocalizacion.de(origen.latitud(), origen.longitud(), origen.pais());
    }

    private static Currency moneda(String codigo) {
        try {
            return Currency.getInstance(codigo.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new EventoInvalidoException("Moneda desconocida: '%s'".formatted(codigo), e);
        }
    }

    private static String exigido(String valor, String campo) {
        if (valor == null || valor.isBlank()) {
            throw new EventoInvalidoException("Falta '%s', obligatorio para este tipo de instrumento".formatted(campo));
        }
        return valor;
    }
}
