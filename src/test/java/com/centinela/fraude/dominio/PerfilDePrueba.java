package com.centinela.fraude.dominio;

import com.centinela.fraude.dominio.modelo.PerfilDeComportamiento;
import com.centinela.fraude.dominio.modelo.vo.Dinero;
import com.centinela.fraude.dominio.modelo.vo.Geolocalizacion;
import com.centinela.fraude.dominio.modelo.vo.IdCliente;
import com.centinela.fraude.dominio.modelo.vo.IdDispositivo;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Constructor fluido de perfiles para tests.
 *
 * <p>{@link PerfilDeComportamiento} tiene diez componentes y ningún test necesita los diez. Sin
 * esto, cada prueba de una regla arrastraría ocho valores irrelevantes y el día que el perfil
 * gane un campo habría que tocar todos los ficheros de test.
 *
 * <p>El estado inicial describe a un cliente <strong>normal y bien conocido</strong>: con esos
 * valores, {@link ObjetosDePrueba#transaccionWeb()} no dispara ninguna regla. Cada test parte de
 * ahí y cambia solo lo que quiere probar, de modo que la señal que salta es necesariamente la
 * que el test provocó.
 */
public final class PerfilDePrueba {

    private IdCliente cliente = ObjetosDePrueba.cliente();
    private Dinero importeMedio = ObjetosDePrueba.euros("100.00");
    private Dinero importeMaximo = ObjetosDePrueba.euros("500.00");
    private Set<String> paises = new LinkedHashSet<>(Set.of("ES"));
    private Set<String> comercios = new LinkedHashSet<>(Set.of("com-100"));
    private Set<IdDispositivo> dispositivos =
            new LinkedHashSet<>(Set.of(IdDispositivo.de("fp-navegador-abc123")));
    private long transacciones = 250;
    private Optional<Instant> ultimaActividad = Optional.of(ObjetosDePrueba.AHORA.minusSeconds(3600));
    private Optional<Geolocalizacion> ultimaPosicion = Optional.of(ObjetosDePrueba.madrid());
    private long version = 7;

    private PerfilDePrueba() {}

    public static PerfilDePrueba unPerfil() {
        return new PerfilDePrueba();
    }

    /** Cliente nuevo: sin historial, sin agregados, sin posición previa. */
    public static PerfilDeComportamiento recienCreado() {
        return PerfilDeComportamiento.nuevoPara(
                ObjetosDePrueba.cliente(), java.util.Currency.getInstance("EUR"), ObjetosDePrueba.AHORA);
    }

    public PerfilDePrueba de(IdCliente otroCliente) {
        this.cliente = otroCliente;
        return this;
    }

    public PerfilDePrueba conTransacciones(long cantidad) {
        this.transacciones = cantidad;
        return this;
    }

    /** Por debajo del mínimo para ser fiable: fuerza la degradación de las reglas que lo exigen. */
    public PerfilDePrueba sinHistorialSuficiente() {
        this.transacciones = PerfilDeComportamiento.MINIMO_PARA_SER_FIABLE - 1;
        return this;
    }

    public PerfilDePrueba conMedia(String importe) {
        this.importeMedio = ObjetosDePrueba.euros(importe);
        return this;
    }

    public PerfilDePrueba conMedia(Dinero importe) {
        this.importeMedio = importe;
        return this;
    }

    public PerfilDePrueba sinMedia() {
        this.importeMedio = Dinero.cero(java.util.Currency.getInstance("EUR"));
        return this;
    }

    public PerfilDePrueba conPaises(String... codigos) {
        this.paises = Arrays.stream(codigos).collect(Collectors.toCollection(LinkedHashSet::new));
        return this;
    }

    public PerfilDePrueba conComercios(String... ids) {
        this.comercios = Arrays.stream(ids).collect(Collectors.toCollection(LinkedHashSet::new));
        return this;
    }

    public PerfilDePrueba conDispositivos(IdDispositivo... conocidos) {
        this.dispositivos = Arrays.stream(conocidos).collect(Collectors.toCollection(LinkedHashSet::new));
        return this;
    }

    /** Dónde y cuándo se vio al titular por última vez. Base de la regla de viaje imposible. */
    public PerfilDePrueba vistoEn(Geolocalizacion posicion, Instant momento) {
        this.ultimaPosicion = Optional.of(posicion);
        this.ultimaActividad = Optional.of(momento);
        return this;
    }

    public PerfilDePrueba enVersion(long version) {
        this.version = version;
        return this;
    }

    public PerfilDePrueba sinPosicionPrevia() {
        this.ultimaPosicion = Optional.empty();
        this.ultimaActividad = Optional.empty();
        return this;
    }

    public PerfilDeComportamiento construir() {
        return new PerfilDeComportamiento(
                cliente,
                importeMedio,
                importeMaximo,
                paises,
                comercios,
                dispositivos,
                transacciones,
                ultimaActividad,
                ultimaPosicion,
                ObjetosDePrueba.AHORA,
                version);
    }
}
