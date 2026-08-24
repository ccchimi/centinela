package com.centinela.fraude.dominio;

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
import java.time.Instant;
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

    private ObjetosDePrueba() {}

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

    public static Geolocalizacion madrid() {
        return Geolocalizacion.de(40.4168, -3.7038, "ES");
    }

    public static Geolocalizacion tokio() {
        return Geolocalizacion.de(35.6762, 139.6503, "JP");
    }

    /** Transacción de comercio electrónico corriente: el caso base de casi todos los tests. */
    public static Transaccion transaccionWeb() {
        return new Transaccion(
                IdTransaccion.de("tx-0001"),
                cliente(),
                euros("120.00"),
                tarjetaNoPresente(),
                comercio(),
                Canal.WEB,
                Optional.of(madrid()),
                Optional.of(IdDispositivo.de("fp-navegador-abc123")),
                AHORA);
    }
}
