package com.centinela.fraude.dominio.modelo;

import com.centinela.fraude.dominio.compartido.Invariantes;
import com.centinela.fraude.dominio.modelo.vo.Canal;
import com.centinela.fraude.dominio.modelo.vo.Comercio;
import com.centinela.fraude.dominio.modelo.vo.Dinero;
import com.centinela.fraude.dominio.modelo.vo.Geolocalizacion;
import com.centinela.fraude.dominio.modelo.vo.IdCliente;
import com.centinela.fraude.dominio.modelo.vo.IdDispositivo;
import com.centinela.fraude.dominio.modelo.vo.IdTransaccion;
import com.centinela.fraude.dominio.modelo.vo.InstrumentoDePago;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Transacción a evaluar. Es el agregado raíz del dominio y la unidad sobre la que el motor
 * emite un veredicto.
 *
 * <h2>Por qué es un record y no una clase con identidad</h2>
 *
 * Conceptualmente es una entidad —tiene {@link IdTransaccion}—, pero es <em>inmutable</em>: una
 * transacción es un hecho ya ocurrido que el motor observa, nunca modifica. Con inmutabilidad,
 * la igualdad estructural que genera el record y la igualdad por identidad coinciden en la
 * práctica: dos cargas útiles con el mismo id y contenido distinto no son "la misma
 * transacción", son un incidente de integridad de datos que conviene que salte. Para comparar
 * solo identidad, sin arrastrar el contenido, está {@link #tieneMismaIdentidadQue(Transaccion)}.
 *
 * <h2>Por qué no se valida aquí que la fecha no sea futura</h2>
 *
 * Validar {@code ocurridaEn} contra {@code Instant.now()} en el constructor metería una
 * dependencia oculta del reloj del sistema dentro del núcleo y volvería no deterministas todos
 * los tests que construyan una transacción. La tolerancia de desfase de reloj es una decisión
 * del caso de uso, que recibe un {@code Clock} inyectado; el dominio solo expone
 * {@link #antiguedadRespectoA(Instant)} para que pueda tomarla.
 *
 * @param origen geolocalización, ausente en canales que no la reportan (API, TELEFONICO)
 * @param dispositivo huella del dispositivo, ausente en POS y CAJERO
 */
public record Transaccion(
        IdTransaccion id,
        IdCliente cliente,
        Dinero importe,
        InstrumentoDePago instrumento,
        Comercio comercio,
        Canal canal,
        Optional<Geolocalizacion> origen,
        Optional<IdDispositivo> dispositivo,
        Instant ocurridaEn) {

    public Transaccion {
        Invariantes.noNulo(id, "transaccion.id");
        Invariantes.noNulo(cliente, "transaccion.cliente");
        Invariantes.noNulo(importe, "transaccion.importe");
        Invariantes.noNulo(instrumento, "transaccion.instrumento");
        Invariantes.noNulo(comercio, "transaccion.comercio");
        Invariantes.noNulo(canal, "transaccion.canal");
        Invariantes.noNulo(ocurridaEn, "transaccion.ocurridaEn");

        // Optional como componente de record: aquí no es un campo de un bean, es un contrato
        // explícito de "este dato puede no venir". Se normaliza null -> empty para que ningún
        // consumidor tenga que defenderse de las dos formas de ausencia.
        origen = origen == null ? Optional.empty() : origen;
        dispositivo = dispositivo == null ? Optional.empty() : dispositivo;

        Invariantes.exigir(!importe.esCero(), "El importe de una transacción a evaluar no puede ser cero");

        // Coherencia canal/instrumento: una tarjeta leída físicamente no puede haber entrado
        // por un canal remoto. Deconstrucción de registro en instanceof (JEP 440).
        if (instrumento instanceof InstrumentoDePago.Tarjeta(_, _, _, boolean presente) && presente) {
            Invariantes.exigir(
                    canal.presenciaFisica(),
                    "Una tarjeta presente es incompatible con el canal %s",
                    canal);
        }
    }

    /** Constructor de conveniencia para transacciones sin geolocalización ni dispositivo. */
    public static Transaccion de(
            IdTransaccion id,
            IdCliente cliente,
            Dinero importe,
            InstrumentoDePago instrumento,
            Comercio comercio,
            Canal canal,
            Instant ocurridaEn) {
        return new Transaccion(
                id, cliente, importe, instrumento, comercio, canal, Optional.empty(), Optional.empty(), ocurridaEn);
    }

    /**
     * Clave de particionado en Kafka: el identificador del titular.
     *
     * <p>Decisión con consecuencias: garantiza que todas las operaciones de un mismo cliente
     * caen en la misma partición y por tanto se procesan en orden y por un único consumidor.
     * Sin eso, los contadores de velocidad ("N operaciones en 60 s") tendrían carreras entre
     * instancias y las reglas de viaje imposible compararían contra un estado desordenado.
     * El coste es el habitual: un cliente muy activo puede crear una partición caliente.
     */
    public String claveDeParticion() {
        return cliente.valor();
    }

    /** Card Not Present: sin presencia física del titular. */
    public boolean esNoPresencial() {
        return canal.esNoPresencial();
    }

    /** El país de origen difiere del país del comercio. Desconocido si no hay geolocalización. */
    public boolean cruzaFrontera() {
        return origen.map(geo -> !geo.mismoPaisQue(comercio.paisIso2())).orElse(false);
    }

    /** Tiempo transcurrido desde que ocurrió hasta el instante de referencia. Nunca negativo. */
    public Duration antiguedadRespectoA(Instant referencia) {
        Invariantes.noNulo(referencia, "instante de referencia");
        Duration transcurrido = Duration.between(ocurridaEn, referencia);
        return transcurrido.isNegative() ? Duration.ZERO : transcurrido;
    }

    /** Comparación por identidad, ignorando el contenido. */
    public boolean tieneMismaIdentidadQue(Transaccion otra) {
        return otra != null && id.equals(otra.id);
    }

    /**
     * Representación para logs y trazas: sin identificador de cliente en claro, sin PAN y sin
     * coordenadas exactas. Es la única forma en que esta transacción debería aparecer en un
     * fichero de log.
     */
    public String descripcionSegura() {
        return "Transaccion[id=%s, cliente=%s, importe=%s, canal=%s, comercio=%s, instrumento=%s]"
                .formatted(id, cliente.enmascarado(), importe, canal, comercio.id(), instrumento.descripcionSegura());
    }
}
