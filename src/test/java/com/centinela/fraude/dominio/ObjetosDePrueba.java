package com.centinela.fraude.dominio;

import com.centinela.fraude.dominio.modelo.PerfilDeComportamiento;
import com.centinela.fraude.dominio.modelo.Transaccion;
import com.centinela.fraude.dominio.modelo.vo.Canal;
import com.centinela.fraude.dominio.modelo.vo.ClaveDeVelocidad;
import com.centinela.fraude.dominio.modelo.vo.Comercio;
import com.centinela.fraude.dominio.modelo.vo.Dinero;
import com.centinela.fraude.dominio.modelo.vo.Geolocalizacion;
import com.centinela.fraude.dominio.modelo.vo.IdCliente;
import com.centinela.fraude.dominio.modelo.vo.IdDispositivo;
import com.centinela.fraude.dominio.modelo.vo.IdTransaccion;
import com.centinela.fraude.dominio.modelo.vo.InstrumentoDePago;
import com.centinela.fraude.dominio.modelo.vo.MarcaDeTarjeta;
import com.centinela.fraude.dominio.regla.ContextoDeEvaluacion;
import com.centinela.fraude.dominio.regla.PoliticaDeRiesgo;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Fábrica de objetos de dominio para tests.
 *
 * <p>Existe para que cada test declare <em>solo</em> lo que le importa. Un test que comprueba el
 * viaje imposible no debería tener que inventarse un MCC, y si lo hace, el día que cambie el
 * constructor hay que tocar cincuenta ficheros.
 */
public final class ObjetosDePrueba {

    public static final Instant AHORA = Instant.parse("2026-08-24T12:00:00Z");

    public static final IdDispositivo DISPOSITIVO_CONOCIDO = IdDispositivo.de("fp-navegador-abc123");
    public static final IdDispositivo DISPOSITIVO_NUEVO = IdDispositivo.de("fp-navegador-zzz999");

    private ObjetosDePrueba() {}

    // ----------------------------------------------------------------------------------
    // Value objects
    // ----------------------------------------------------------------------------------

    public static IdCliente cliente() {
        return IdCliente.de("cliente-000042");
    }

    public static Comercio comercio() {
        return Comercio.de("com-100", "Libreria Central", "5942", "ES");
    }

    public static Comercio comercioDeAltoRiesgo() {
        return Comercio.de("com-999", "Casa de Apuestas", "7995", "MT");
    }

    public static Dinero euros(String importe) {
        return Dinero.de(importe, "EUR");
    }

    public static InstrumentoDePago tarjetaNoPresente() {
        return new InstrumentoDePago.Tarjeta("454213", "1881", MarcaDeTarjeta.VISA, false);
    }

    public static InstrumentoDePago tarjetaPresente() {
        return new InstrumentoDePago.Tarjeta("454213", "1881", MarcaDeTarjeta.VISA, true);
    }

    public static InstrumentoDePago billeteraTokenizada() {
        return new InstrumentoDePago.BilleteraDigital("ApplePay", DISPOSITIVO_CONOCIDO, true);
    }

    public static InstrumentoDePago billeteraSinTokenizar() {
        return new InstrumentoDePago.BilleteraDigital("MonederoX", DISPOSITIVO_CONOCIDO, false);
    }

    public static InstrumentoDePago transferencia() {
        return new InstrumentoDePago.TransferenciaBancaria("ES91****1234", "BSCHESMM");
    }

    public static Geolocalizacion madrid() {
        return Geolocalizacion.de(40.4168, -3.7038, "ES");
    }

    public static Geolocalizacion tokio() {
        return Geolocalizacion.de(35.6762, 139.6503, "JP");
    }

    // ----------------------------------------------------------------------------------
    // Transacciones
    // ----------------------------------------------------------------------------------

    /**
     * Transacción de comercio electrónico corriente, coherente con el perfil por defecto de
     * {@link PerfilDePrueba}: mismo país, mismo comercio, mismo dispositivo e importe dentro de
     * la media. Sobre ella el catálogo completo no debe disparar ninguna regla.
     */
    public static Transaccion transaccionWeb() {
        return transaccion(euros("120.00"), tarjetaNoPresente(), comercio(), Canal.WEB, madrid(), DISPOSITIVO_CONOCIDO);
    }

    public static Transaccion transaccion(
            Dinero importe,
            InstrumentoDePago instrumento,
            Comercio comercio,
            Canal canal,
            Geolocalizacion origen,
            IdDispositivo dispositivo) {
        return new Transaccion(
                IdTransaccion.de("tx-0001"),
                cliente(),
                importe,
                instrumento,
                comercio,
                canal,
                Optional.ofNullable(origen),
                Optional.ofNullable(dispositivo),
                AHORA);
    }

    public static Transaccion transaccionDe(Dinero importe) {
        return transaccion(importe, tarjetaNoPresente(), comercio(), Canal.WEB, madrid(), DISPOSITIVO_CONOCIDO);
    }

    public static Transaccion transaccionDesde(Geolocalizacion origen) {
        return transaccion(euros("120.00"), tarjetaNoPresente(), comercio(), Canal.WEB, origen, DISPOSITIVO_CONOCIDO);
    }

    public static Transaccion transaccionCon(InstrumentoDePago instrumento) {
        return transaccion(euros("120.00"), instrumento, comercio(), Canal.WEB, madrid(), DISPOSITIVO_CONOCIDO);
    }

    /**
     * Transacción típica del canal indicado, con los datos que ese canal reporta de verdad: un
     * cajero da posición pero no huella de navegador, y una integración por API no da ninguna
     * de las dos. Si el fixture rellenara siempre ambos campos, los tests de "no aplica"
     * pasarían por el camino equivocado.
     */
    public static Transaccion transaccionEn(Canal canal) {
        boolean reportaPosicion = canal != Canal.API && canal != Canal.TELEFONICO;
        return transaccion(
                euros("120.00"),
                canal.presenciaFisica() ? tarjetaPresente() : tarjetaNoPresente(),
                comercio(),
                canal,
                reportaPosicion ? madrid() : null,
                canal.soportaHuellaDeDispositivo() ? DISPOSITIVO_CONOCIDO : null);
    }

    public static Transaccion transaccionOcurridaEn(Instant momento) {
        return new Transaccion(
                IdTransaccion.de("tx-0001"),
                cliente(),
                euros("120.00"),
                tarjetaNoPresente(),
                comercio(),
                Canal.WEB,
                Optional.of(madrid()),
                Optional.of(DISPOSITIVO_CONOCIDO),
                momento);
    }

    // ----------------------------------------------------------------------------------
    // Contextos de evaluación
    // ----------------------------------------------------------------------------------

    /** Contexto con el perfil por defecto y sin contadores resueltos. */
    public static ContextoDeEvaluacion contexto(Transaccion transaccion) {
        return contexto(transaccion, PerfilDePrueba.unPerfil().construir(), Map.of());
    }

    public static ContextoDeEvaluacion contexto(Transaccion transaccion, PerfilDeComportamiento perfil) {
        return contexto(transaccion, perfil, Map.of());
    }

    public static ContextoDeEvaluacion contexto(
            Transaccion transaccion, PerfilDeComportamiento perfil, Map<ClaveDeVelocidad, Long> contadores) {
        return new ContextoDeEvaluacion(transaccion, perfil, contadores, PoliticaDeRiesgo.porDefecto(), AHORA);
    }

    public static ContextoDeEvaluacion contexto(
            Transaccion transaccion,
            PerfilDeComportamiento perfil,
            Map<ClaveDeVelocidad, Long> contadores,
            PoliticaDeRiesgo politica) {
        return new ContextoDeEvaluacion(transaccion, perfil, contadores, politica, AHORA);
    }
}
