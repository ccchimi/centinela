package com.centinela.fraude.arquitectura;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.Architectures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Las reglas de la arquitectura hexagonal, ejecutables.
 *
 * <p>Un diagrama en un wiki se incumple en la tercera semana y nadie se entera. Esto falla el
 * build. Es la diferencia entre tener una arquitectura y haberla dibujado.
 */
@DisplayName("Arquitectura hexagonal")
class ArquitecturaHexagonalTest {

    private static final String RAIZ = "com.centinela.fraude";

    private static JavaClasses clases;

    @BeforeAll
    static void importarClases() {
        clases = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests()).importPackages(RAIZ);
    }

    @Test
    @DisplayName("el dominio no depende de ningún framework")
    void elDominioNoDependeDeFrameworks() {
        noClasses()
                .that()
                .resideInAPackage("..dominio..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "org.apache.kafka..",
                        "software.amazon..",
                        "com.fasterxml..",
                        "tools.jackson..",
                        "jakarta..",
                        "javax..",
                        "org.slf4j..",
                        "org.mapstruct..")
                .because("el núcleo debe poder ejecutarse en un test unitario sin contexto ni contenedores")
                .check(clases);
    }

    @Test
    @DisplayName("el dominio no conoce las capas exteriores")
    void elDominioNoConoceLasCapasExteriores() {
        noClasses()
                .that()
                .resideInAPackage("..dominio..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..aplicacion..", "..infraestructura..")
                .because("la regla de dependencia del hexágono apunta siempre hacia dentro")
                .check(clases);
    }

    @Test
    @DisplayName("la aplicación no conoce los adaptadores")
    void laAplicacionNoConoceLosAdaptadores() {
        noClasses()
                .that()
                .resideInAPackage("..aplicacion..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..infraestructura..")
                .because("los casos de uso se cablean por inversión de dependencias, no por importación")
                .check(clases);
    }

    @Test
    @DisplayName("las capas respetan la dirección de las dependencias")
    void lasCapasRespetanLaDireccionDeLasDependencias() {
        Architectures.layeredArchitecture()
                .consideringOnlyDependenciesInLayers()
                .layer("Dominio")
                .definedBy(RAIZ + ".dominio..")
                .layer("Aplicacion")
                .definedBy(RAIZ + ".aplicacion..")
                .layer("Infraestructura")
                .definedBy(RAIZ + ".infraestructura..")
                .whereLayer("Infraestructura")
                .mayNotBeAccessedByAnyLayer()
                .whereLayer("Aplicacion")
                .mayOnlyBeAccessedByLayers("Infraestructura")
                .check(clases);
    }

    @Test
    @DisplayName("los puertos de salida son interfaces")
    void losPuertosDeSalidaSonInterfaces() {
        classes()
                .that()
                .resideInAPackage("..dominio.puerto..")
                .and()
                .areTopLevelClasses()
                .should()
                .beInterfaces()
                .because("un puerto es un contrato; cualquier implementación pertenece a otra capa")
                .check(clases);
    }
}
