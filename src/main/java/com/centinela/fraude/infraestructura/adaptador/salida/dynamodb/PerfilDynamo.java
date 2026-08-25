package com.centinela.fraude.infraestructura.adaptador.salida.dynamodb;

import com.centinela.fraude.dominio.excepcion.ErrorDeDominio;
import com.centinela.fraude.dominio.modelo.PerfilDeComportamiento;
import com.centinela.fraude.dominio.modelo.vo.Dinero;
import com.centinela.fraude.dominio.modelo.vo.Geolocalizacion;
import com.centinela.fraude.dominio.modelo.vo.IdCliente;
import com.centinela.fraude.dominio.modelo.vo.IdDispositivo;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Traducción entre {@link PerfilDeComportamiento} y el item de DynamoDB.
 *
 * <p>Toda la forma de la tabla vive aquí: si mañana se renombra un atributo o se parte el perfil
 * en dos items, este es el único fichero que cambia. El dominio no sabe que existe una tabla.
 */
final class PerfilDynamo {

    static final String CLAVE = "idCliente";

    private static final String IMPORTE_MEDIO = "importeMedio";
    private static final String IMPORTE_MAXIMO = "importeMaximoHistorico";
    private static final String PAISES = "paisesHabituales";
    private static final String COMERCIOS = "comerciosHabituales";
    private static final String DISPOSITIVOS = "dispositivosConocidos";
    private static final String TRANSACCIONES = "transaccionesObservadas";
    private static final String ULTIMA_ACTIVIDAD = "ultimaActividad";
    private static final String ULTIMA_POSICION = "ultimaPosicion";
    static final String ACTUALIZADO_EN = "actualizadoEn";

    private static final String IMPORTE = "importe";
    private static final String MONEDA = "moneda";
    private static final String LATITUD = "latitud";
    private static final String LONGITUD = "longitud";
    private static final String PAIS = "pais";

    private PerfilDynamo() {}

    static Map<String, AttributeValue> clavePara(IdCliente cliente) {
        return Map.of(CLAVE, Atributos.texto(cliente.valor()));
    }

    static Map<String, AttributeValue> aItem(PerfilDeComportamiento perfil) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(CLAVE, Atributos.texto(perfil.cliente().valor()));
        item.put(IMPORTE_MEDIO, aDinero(perfil.importeMedio()));
        item.put(IMPORTE_MAXIMO, aDinero(perfil.importeMaximoHistorico()));
        item.put(PAISES, Atributos.listaDeTextos(perfil.paisesHabituales()));
        item.put(COMERCIOS, Atributos.listaDeTextos(perfil.comerciosHabituales()));
        item.put(
                DISPOSITIVOS,
                Atributos.lista(perfil.dispositivosConocidos(), d -> Atributos.texto(d.huella())));
        item.put(TRANSACCIONES, Atributos.numero(perfil.transaccionesObservadas()));
        item.put(ACTUALIZADO_EN, Atributos.momento(perfil.actualizadoEn()));

        // Los opcionales se omiten en vez de escribirse como NULL: un atributo ausente ocupa
        // cero y las expresiones de condición pueden preguntar por attribute_not_exists.
        perfil.ultimaActividad().ifPresent(momento -> item.put(ULTIMA_ACTIVIDAD, Atributos.momento(momento)));
        perfil.ultimaPosicion().ifPresent(posicion -> item.put(ULTIMA_POSICION, aPosicion(posicion)));

        return Map.copyOf(item);
    }

    static PerfilDeComportamiento aDominio(Map<String, AttributeValue> item) {
        try {
            return new PerfilDeComportamiento(
                    IdCliente.de(Atributos.texto(item, CLAVE)),
                    aDinero(Atributos.mapa(item, IMPORTE_MEDIO)),
                    aDinero(Atributos.mapa(item, IMPORTE_MAXIMO)),
                    new LinkedHashSet<>(Atributos.listaDeTextos(item, PAISES)),
                    new LinkedHashSet<>(Atributos.listaDeTextos(item, COMERCIOS)),
                    aDispositivos(item),
                    Atributos.numeroLargo(item, TRANSACCIONES),
                    Atributos.momentoOpcional(item, ULTIMA_ACTIVIDAD),
                    Atributos.mapaOpcional(item, ULTIMA_POSICION).map(PerfilDynamo::aPosicion),
                    Atributos.momento(item, ACTUALIZADO_EN));
        } catch (ErrorDeDominio e) {
            // El item existe pero no cumple las invariantes del modelo. Traducir el error a uno
            // de infraestructura evita que parezca que el problema está en la transacción que
            // se está evaluando: el dato malo lleva ahí desde antes.
            throw new ItemCorruptoException(
                    "El perfil almacenado no cumple las invariantes del dominio: " + e.getMessage(), e);
        }
    }

    private static Set<IdDispositivo> aDispositivos(Map<String, AttributeValue> item) {
        return Atributos.listaDeTextos(item, DISPOSITIVOS).stream()
                .map(IdDispositivo::de)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static AttributeValue aDinero(Dinero dinero) {
        return Atributos.mapa(Map.of(
                IMPORTE, Atributos.numero(dinero.importe()),
                MONEDA, Atributos.texto(dinero.moneda().getCurrencyCode())));
    }

    private static Dinero aDinero(Map<String, AttributeValue> mapa) {
        return Dinero.de(
                Atributos.numeroExacto(mapa, IMPORTE), Currency.getInstance(Atributos.texto(mapa, MONEDA)));
    }

    private static AttributeValue aPosicion(Geolocalizacion posicion) {
        return Atributos.mapa(Map.of(
                LATITUD, Atributos.numero(posicion.latitud()),
                LONGITUD, Atributos.numero(posicion.longitud()),
                PAIS, Atributos.texto(posicion.paisIso2())));
    }

    private static Geolocalizacion aPosicion(Map<String, AttributeValue> mapa) {
        return Geolocalizacion.de(
                Atributos.numeroDecimal(mapa, LATITUD),
                Atributos.numeroDecimal(mapa, LONGITUD),
                Atributos.texto(mapa, PAIS));
    }
}
