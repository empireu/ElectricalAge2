## Physical Fluid & Transformation Standards

The Fluid Handling in ELN2 was designed to be compatible with Forge Fluids and other mods.
This constraint doesn't allow us to have pressure. We are only able to attach temperature data to fluid transfers, using some conventions about coercing mass between the thermal world (ELN2) and the non-thermal world (other mods).
Due to the absence of pressure, we can't load real-world datasets for fluids and transformations; we have simplifications.
To make sure we don't create mass or energy, we need to follow these conventions to the letter:

### 1. The Millibucket-Mass Equivalence
We treat the Millibucket (mB) as a **Unit of Mass**, specifically anchored to the substance's **Liquid Phase**.

* **1 mB** $\equiv$ Mass of **1 Liter** of the substance in its **Liquid State** at 1 atmosphere.
    * *Standard Liquids:* For Water, Diesel, or Oil, this is their density at STP (to make importing data easier).
    * *When the Fluid is a Gas at STP:* For substances like Methane, Propane, Butane, Liquid Nitrogen, use the density of their **Cryogenic Liquid form**.
* **1 mB Gas** $\equiv$ **1 mB Liquid Equivalent**.
    * A `FluidStack` of 1 mB Gas has the **same mass** as 1 mB of its liquid form.
    * This is because we don't do volume expansion (it doesn't make sense without also having pressure), and also makes gameplay easier.

### 2. Fluid Data (`data/eln2/physical_fluid/`)
Derive all properties from the **Liquid State Density**, even for fluids that are naturally gaseous.

* **Density (`density`):**
    * **Value:** Real-world density of the **Liquid Phase** in **kg/L** (thanks to **1 mB** $\equiv$ **1L**)
    * *Example (Diesel):* 0.82 kg/L $\rightarrow$ `0.82`.
    * *Example (Methane):* Liquid Methane is ~0.42 kg/L $\rightarrow$ `0.42`.
    * **Gases:** _Must_ use this same Liquid Density, otherwise the mass equivalence would be broken.

* **Specific Heat (`specificHeatCapacity`):**
    * Defined in $J / (mB \cdot K)$.
    * **Formula:**
      $$Cp_{eln2} = Cp_{real\_gravimetric} \times Density_{liquid\_anchor}$$
    * *Example:* Methane Gas ($Cp \approx 2200 \text{ J/kgK}$).
      $$2200 \times 0.42 = 924\ J/(mB \cdot K)$$

### 3. Transformations and Enthalpy
Phase changes obey strict energy conservation. This is very important, as we don't want an exploit that generates free energy.

* **Enthalpy (`enthalpy`):**
    * Defined as **Joules per Input mB**.
* **Standard Phase Change (1:1):**
    * Use the real-world Enthalpy of Vaporization (scaled to 1 L/kg).
    * *Example:* Water $\to$ Steam ($2260 kJ/kg$). Enthalpy = `2,260,000`.
* **Fractional Distillation:**
    * If boiling splits a fluid (e.g., Oil $\to$ Naphtha Gas + Heavy Oil), you **only pay enthalpy for the portion that becomes gas**.
    * **Formula:**
      $$Enthalpy_{eln2} = Enthalpy_{real} \times \frac{GasProportion}{1000}$$
    * *Example:* Distilling Heavy Oil (Enthalpy ~300kJ). If yield is 60% Gas / 40% Heavy Oil:
      $$Enthalpy = 300,000 \times 0.6 = 180,000$$
    * *Reasoning:* The Heavy Oil remains liquid; it retains its sensible heat and does not require Latent Heat of Vaporization.

### 4. Naming Conventions
We use different naming conventions for liquids at STP and gases at STP:
* **Liquids at STP:**
    * Liquid Form - simply the usual name:
      * *Example:* Water (`minecraft:water`), Naphtha (`eln2:naphtha`)
    * Gas Form - apply the suffix "gas":
      * *Example:* Naphtha Gas (`eln2:naphtha_gas`)
* **Gases at STP:**
    * Liquid (Cryogenic) Form - apply the prefix "liquid":
      * *Example:* Liquid Hydrogen (`eln2:liquid_hydrogen`)
    * Gas Form - simply the usual name:
      * *Example:* Hydrogen (`eln2:hydrogen`)
