package com.centinela.fraude.infraestructura.adaptador.entrada.rest;

import com.centinela.fraude.dominio.excepcion.RecursoDeDominioNoEncontrado;
import com.centinela.fraude.dominio.modelo.vo.IdCliente;
import com.centinela.fraude.dominio.puerto.salida.RepositorioDePerfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consulta del perfil de comportamiento, para la herramienta de analistas.
 *
 * <p>Es de sólo lectura a propósito. El perfil lo construye el motor a partir de lo que ve; que
 * un humano pudiera editarlo por HTTP abriría la puerta a que alguien "limpiara" el historial de
 * un cliente antes de una operación, que es exactamente el fraude interno contra el que este
 * sistema debería protegerse.
 *
 * <p>Depende del puerto de salida y no de un caso de uso porque no hay ninguna decisión que
 * tomar: es una proyección directa del estado. Inventar un caso de uso que sólo delega sería
 * una capa sin contenido.
 */
@RestController
@RequestMapping("/api/v1/perfiles")
public class ControladorDePerfiles {

    private final RepositorioDePerfiles perfiles;

    public ControladorDePerfiles(RepositorioDePerfiles perfiles) {
        this.perfiles = perfiles;
    }

    @GetMapping("/{idCliente}")
    public PerfilRespuesta consultar(@PathVariable String idCliente) {
        return perfiles.buscarPor(IdCliente.de(idCliente))
                .map(PerfilRespuesta::desde)
                // Un cliente sin perfil es un 404 aquí, y no el "perfil vacío" que usa el motor:
                // el analista necesita saber que no hay historial, no recibir ceros que parecen
                // datos reales.
                .orElseThrow(() -> RecursoDeDominioNoEncontrado.de("perfil", idCliente));
    }
}
