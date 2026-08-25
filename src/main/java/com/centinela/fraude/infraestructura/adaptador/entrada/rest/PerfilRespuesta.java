package com.centinela.fraude.infraestructura.adaptador.entrada.rest;

import com.centinela.fraude.dominio.modelo.PerfilDeComportamiento;
import com.centinela.fraude.dominio.modelo.vo.IdDispositivo;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Representación REST del perfil de comportamiento, para la herramienta de analistas.
 *
 * <p><strong>El identificador de cliente sale enmascarado</strong> y las huellas de dispositivo
 * también. Quien consulta este endpoint ya sabe a qué cliente está mirando —lo acaba de escribir
 * en la URL—, así que devolver los valores en claro sólo añadiría dato personal a los logs de
 * acceso, a las cachés intermedias y a cualquier captura de pantalla.
 *
 * @param version número de versión del bloqueo optimista; útil para diagnosticar carreras
 */
public record PerfilRespuesta(
        String cliente,
        String importeMedio,
        String importeMaximoHistorico,
        Set<String> paisesHabituales,
        int comerciosHabituales,
        List<String> dispositivosConocidos,
        long transaccionesObservadas,
        boolean fiable,
        Instant ultimaActividad,
        Instant actualizadoEn,
        long version) {

    public static PerfilRespuesta desde(PerfilDeComportamiento perfil) {
        return new PerfilRespuesta(
                perfil.cliente().enmascarado(),
                perfil.importeMedio().toString(),
                perfil.importeMaximoHistorico().toString(),
                perfil.paisesHabituales(),
                // Sólo el recuento: la lista completa de comercios de un cliente es un perfil de
                // consumo, y este endpoint existe para investigar fraude, no para eso.
                perfil.comerciosHabituales().size(),
                perfil.dispositivosConocidos().stream().map(IdDispositivo::toString).toList(),
                perfil.transaccionesObservadas(),
                perfil.esFiable(),
                perfil.ultimaActividad().orElse(null),
                perfil.actualizadoEn(),
                perfil.version());
    }
}
