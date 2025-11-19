@file:Suppress("unused")

package org.eln2.mc.common.content.modules

import net.minecraft.world.phys.AABB
import org.ageseries.libage.data.CELSIUS
import org.ageseries.libage.data.HENRY
import org.ageseries.libage.data.KILO
import org.ageseries.libage.data.KILOGRAM
import org.ageseries.libage.data.KILOGRAM_METER2
import org.ageseries.libage.data.METER
import org.ageseries.libage.data.MILLI
import org.ageseries.libage.data.NEWTON_METER
import org.ageseries.libage.data.NEWTON_METER_PER_AMPERE
import org.ageseries.libage.data.OHM
import org.ageseries.libage.data.Quantity
import org.ageseries.libage.data.REVOLUTION_PER_SECOND
import org.ageseries.libage.data.VOLT
import org.ageseries.libage.data.VOLT_PER_RADIAN_PER_SECOND
import org.ageseries.libage.data.WATT
import org.ageseries.libage.mathematics.geometry.BoundingBox3d
import org.ageseries.libage.mathematics.geometry.Vector3d
import org.ageseries.libage.sim.ChemicalElement
import org.ageseries.libage.sim.ConnectionParameters
import org.ageseries.libage.sim.ThermalMassDefinition
import org.eln2.mc.FrictionNodeDescription
import org.eln2.mc.client.render.FlwModels
import org.eln2.mc.common.blocks.BlockRegistry.blockEntityOnly
import org.eln2.mc.common.blocks.BlockRegistry.blockItemOnly
import org.eln2.mc.common.blocks.BlockRegistry.blockOnly
import org.eln2.mc.common.blocks.BlockRegistry.defineDelegateMap
import org.eln2.mc.common.blocks.foundation.BigBlockItem
import org.eln2.mc.common.cells.CellRegistry.cellMemoize
import org.eln2.mc.common.cells.foundation.CellFactory
import org.eln2.mc.common.cells.foundation.ElectricalSize
import org.eln2.mc.common.cells.foundation.KineticSize
import org.eln2.mc.common.content.ContentModule
import org.eln2.mc.common.content.DcMotorCell
import org.eln2.mc.common.content.DcMotorOptions
import org.eln2.mc.common.content.DcMotorPart
import org.eln2.mc.common.content.DcMotorSoundOptions
import org.eln2.mc.common.content.DoubleJointCell
import org.eln2.mc.common.content.JointPart
import org.eln2.mc.common.content.TripleJointCell
import org.eln2.mc.common.content.WindTurbine3dModel
import org.eln2.mc.common.content.WindTurbineBlock
import org.eln2.mc.common.content.WindTurbineBlockEntity
import org.eln2.mc.common.content.WindTurbineCell
import org.eln2.mc.common.content.WindTurbineOptions
import org.eln2.mc.common.parts.PartRegistry.partImmediateBB
import org.eln2.mc.common.parts.PartRegistry.partMemoizeBB
import org.eln2.mc.common.parts.foundation.PartFactory
import org.eln2.mc.common.sounds.SoundRegistry.soundEventVariableRange
import org.eln2.mc.data.directionPoleMapPlanar
import org.eln2.mc.data.monopolarMapPlanar
import org.eln2.mc.mathematics.Base6Direction3d
import org.eln2.mc.mathematics.Base6Direction3dMask

object Eln2Kinetic : ContentModule() {
    //#region Joints

    val JOINT_SOUND = soundEventVariableRange("shaft")

    val STANDARD_IRON_DOUBLE_JOINT_CELL_PLUS = Base6Direction3d.Front
    val STANDARD_IRON_DOUBLE_JOINT_CELL_MINUS = Base6Direction3d.Back

    val STANDARD_IRON_DOUBLE_JOINT_CELL = cellMemoize("standard_iron_double_joint") {
        val thermal = ThermalMassDefinition(
            ChemicalElement.Iron.asMaterial,
            mass = Quantity(60.25, KILOGRAM)
        )

        val map = directionPoleMapPlanar(
            STANDARD_IRON_DOUBLE_JOINT_CELL_PLUS,
            STANDARD_IRON_DOUBLE_JOINT_CELL_MINUS
        )

        val friction = FrictionNodeDescription(
            Quantity(0.0770, KILOGRAM_METER2),
            0.01,
            Quantity(1e-5, NEWTON_METER),
            Quantity(0.1, NEWTON_METER)
        )

        CellFactory {
            DoubleJointCell(it,
                thermal,
                map,
                friction,
                1.0,
                Quantity(120.0, REVOLUTION_PER_SECOND),
                Quantity(750.0, NEWTON_METER)
            )
        }
    }

    val STANDARD_IRON_DOUBLE_JOINT_90DEG_CELL_PLUS = Base6Direction3d.Front
    val STANDARD_IRON_DOUBLE_JOINT_90DEG_CELL_MINUS = Base6Direction3d.Left

    val STANDARD_IRON_DOUBLE_JOINT_90DEG_CELL = cellMemoize("standard_iron_double_joint_90deg") {
        val thermal = ThermalMassDefinition(
            ChemicalElement.Iron.asMaterial,
            mass = Quantity(60.25, KILOGRAM)
        )

        val map = directionPoleMapPlanar(
            STANDARD_IRON_DOUBLE_JOINT_90DEG_CELL_PLUS,
            STANDARD_IRON_DOUBLE_JOINT_90DEG_CELL_MINUS
        )

        val friction = FrictionNodeDescription(
            Quantity(0.11025, KILOGRAM_METER2),
            0.0125,
            Quantity(1e-5, NEWTON_METER),
            Quantity(0.1, NEWTON_METER)
        )

        CellFactory {
            DoubleJointCell(it,
                thermal,
                map,
                friction,
                1.0,
                Quantity(115.0, REVOLUTION_PER_SECOND),
                Quantity(679.0, NEWTON_METER)
            )
        }
    }

    val STANDARD_IRON_DOUBLE_JOINT_90DEG_2X_CELL_PLUS = Base6Direction3d.Front
    val STANDARD_IRON_DOUBLE_JOINT_90DEG_2X_CELL_MINUS = Base6Direction3d.Left

    val STANDARD_IRON_DOUBLE_JOINT_90DEG_2X_CELL = cellMemoize("standard_iron_double_joint_90deg_2x") {
        val thermal = ThermalMassDefinition(
            ChemicalElement.Iron.asMaterial,
            mass = Quantity(60.25, KILOGRAM)
        )

        val map = directionPoleMapPlanar(
            STANDARD_IRON_DOUBLE_JOINT_90DEG_2X_CELL_PLUS,
            STANDARD_IRON_DOUBLE_JOINT_90DEG_2X_CELL_MINUS
        )

        val friction = FrictionNodeDescription(
            Quantity(0.11025, KILOGRAM_METER2),
            0.01567,
            Quantity(1e-5, NEWTON_METER),
            Quantity(0.1, NEWTON_METER)
        )

        CellFactory {
            DoubleJointCell(it,
                thermal,
                map,
                friction,
                2.0,
                Quantity(114.14, REVOLUTION_PER_SECOND),
                Quantity(658.435, NEWTON_METER)
            )
        }
    }

    val STANDARD_IRON_TRIPLE_JOINT_CELL_E1 = Base6Direction3d.Front
    val STANDARD_IRON_TRIPLE_JOINT_CELL_E2 = Base6Direction3d.Left
    val STANDARD_IRON_TRIPLE_JOINT_CELL_E3 = Base6Direction3d.Right

    val STANDARD_IRON_TRIPLE_JOINT_CELL = cellMemoize("standard_iron_triple_joint") {
        val thermal = ThermalMassDefinition(
            ChemicalElement.Iron.asMaterial,
            mass = Quantity(90.25, KILOGRAM)
        )

        val map1 = monopolarMapPlanar(STANDARD_IRON_TRIPLE_JOINT_CELL_E1)
        val map2 = monopolarMapPlanar(STANDARD_IRON_TRIPLE_JOINT_CELL_E2)
        val map3 = monopolarMapPlanar(STANDARD_IRON_TRIPLE_JOINT_CELL_E3)

        val friction = FrictionNodeDescription(
            Quantity(0.13025, KILOGRAM_METER2),
            0.02,
            Quantity(1e-5, NEWTON_METER),
            Quantity(0.1, NEWTON_METER)
        )

        CellFactory {
            TripleJointCell(it,
                thermal,
                map1,
                map2,
                map3,
                friction,
                Quantity(100.0, REVOLUTION_PER_SECOND),
                Quantity(700.0, NEWTON_METER)
            )
        }
    }

    val STANDARD_IRON_DOUBLE_JOINT_PART = partImmediateBB("standard_iron_double_joint", 6.0, 10.0, 16.0) {
        JointPart(
            it,
            STANDARD_IRON_DOUBLE_JOINT_CELL,
            STANDARD_IRON_DOUBLE_JOINT_CELL_PLUS + STANDARD_IRON_DOUBLE_JOINT_CELL_MINUS
        )
    }

    val STANDARD_IRON_DOUBLE_JOINT_90DEG_PART = partImmediateBB("standard_iron_double_joint_90deg", 16.0, 10.0, 16.0) {
        JointPart(
            it,
            STANDARD_IRON_DOUBLE_JOINT_90DEG_CELL,
            STANDARD_IRON_DOUBLE_JOINT_90DEG_CELL_PLUS + STANDARD_IRON_DOUBLE_JOINT_90DEG_CELL_MINUS
        )
    }

    val STANDARD_IRON_DOUBLE_JOINT_90DEG_2X_PART = partImmediateBB("standard_iron_double_joint_90deg_2x", 16.0, 10.0, 16.0) {
        JointPart(
            it,
            STANDARD_IRON_DOUBLE_JOINT_90DEG_2X_CELL,
            STANDARD_IRON_DOUBLE_JOINT_90DEG_2X_CELL_PLUS + STANDARD_IRON_DOUBLE_JOINT_90DEG_2X_CELL_MINUS)
    }

    val STANDARD_IRON_TRIPLE_T_JOINT_PART = partImmediateBB("standard_iron_triple_t_joint", 16.0, 10.0, 16.0) {
        JointPart(
            it,
            STANDARD_IRON_TRIPLE_JOINT_CELL,
            STANDARD_IRON_TRIPLE_JOINT_CELL_E1 + STANDARD_IRON_TRIPLE_JOINT_CELL_E2 + STANDARD_IRON_TRIPLE_JOINT_CELL_E3
        )
    }

    //#endregion

    //#region Motors

    val MOTOR_KINETIC_SOUND = soundEventVariableRange("motor.kinetic")
    val MOTOR_ELECTROMAGNETIC_SOUND = soundEventVariableRange("motor.electromagnetic")

    val BASIC_DC_MOTOR_CELL_DIRECTION = Base6Direction3d.Front

    val BASIC_DC_MOTOR_CELL = cellMemoize("basic_dc_motor") {
        val electricalMap = directionPoleMapPlanar(Base6Direction3d.Left, Base6Direction3d.Right)
        val kineticMap = monopolarMapPlanar(BASIC_DC_MOTOR_CELL_DIRECTION)

        val electricalSize = ElectricalSize.Standard
        val kineticSize = KineticSize.Standard

        val model = DcMotorOptions(
            FrictionNodeDescription(
                Quantity(3.1278, KILOGRAM_METER2),
                0.01,
                Quantity(1.0),
                Quantity(0.1)
            ),
            1e-6,
            Quantity(0.0667, OHM),
            Quantity(1.25, MILLI * HENRY),
            Quantity(2.06, VOLT_PER_RADIAN_PER_SECOND),
            Quantity(2.05, NEWTON_METER_PER_AMPERE),
            Quantity(100.0, REVOLUTION_PER_SECOND),
            Quantity(781.691, VOLT),
            Quantity(173.25, CELSIUS)
        )

        val thermalDef = ThermalMassDefinition(
            ChemicalElement.Iron.asMaterial,
            mass = Quantity(2.1451, KILOGRAM) // Mass of the wiring itself. Not the entire rotor.
        )

        val leakage = ConnectionParameters.DEFAULT

        CellFactory {
            DcMotorCell(it,
                electricalMap, kineticMap,
                electricalSize, kineticSize,
                model,
                thermalDef, leakage
            )
        }
    }

    val BASIC_DC_MOTOR_PART = partMemoizeBB("basic_dc_motor", 16.0, 13.0, 14.3) {
        val soundOptions = DcMotorSoundOptions(
            Quantity(10.0, REVOLUTION_PER_SECOND),
            Quantity(12.0, KILO * WATT)
        )

        PartFactory {
            DcMotorPart(
                it,
                soundOptions,
                BASIC_DC_MOTOR_CELL,
                Base6Direction3dMask.ofRelative(BASIC_DC_MOTOR_CELL_DIRECTION)
            )
        }
    }

    //#endregion

    //#region Wind Turbine

    val BASIC_WIND_TURBINE_CELL = cellMemoize("basic_wind_turbine") {
        val options = WindTurbineOptions(
            BoundingBox3d.fromCenterSize(
                Vector3d.zero,
                16.0
            ),
            BoundingBox3d.fromCenterSize(
                Vector3d.zero,
                Vector3d(36.0 / 16.0, 77.0 / 16.0, 36.0 / 16.0)
            ),
            FrictionNodeDescription(
                Quantity(12.5, KILOGRAM_METER2),
                1.251,
                Quantity(0.01),
                Quantity(0.1)
            ),
            Quantity(1.5, METER),
            0.89159015861095
        )

        CellFactory {
            WindTurbineCell(it, options)
        }

    }

    val BASIC_WIND_TURBINE_DELEGATE_MAP = defineDelegateMap("basic_wind_turbine") {
        val fullBlock = registerDelegateOf(
            AABB(
                0.0, 0.0, 0.0,
                1.0, 1.0, 1.0
            )
        )

        fun defineSlab(y: Int) {
            for(x in -1..1) {
                for(z in -1..1) {
                    principal(x, y, z, fullBlock)
                }
            }
        }

        defineSlab(1)
        defineSlab(2)
        defineSlab(3)
        defineSlab(4)
        defineSlab(5)
    }

    val BASIC_WIND_TURBINE_BLOCK = blockOnly("basic_wind_turbine") {
        WindTurbineBlock(
            BASIC_WIND_TURBINE_CELL,
            BASIC_WIND_TURBINE_DELEGATE_MAP.value,
            WindTurbine3dModel(
                FlwModels.BASIC_WIND_TURBINE_BASE,
                FlwModels.BASIC_WIND_TURBINE_ROTOR
            )
        )
    }

    val BASIC_WIND_TURBINE_BLOCK_ITEM = blockItemOnly("basic_wind_turbine") {
        BigBlockItem(
            BASIC_WIND_TURBINE_DELEGATE_MAP.value,
            BASIC_WIND_TURBINE_BLOCK.get()
        )
    }

    // Single block entity for all turbines (implement new turbines by adding only a new block)
    val WIND_TURBINE_BLOCK_ENTITY = blockEntityOnly(
        "wind_turbine",
        BASIC_WIND_TURBINE_BLOCK,
        ::WindTurbineBlockEntity
    )

    //#endregion
}
