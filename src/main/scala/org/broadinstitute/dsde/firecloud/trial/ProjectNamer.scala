package org.broadinstitute.dsde.firecloud.trial

import scala.util.Random.nextInt

object ProjectNamer {

  private final val elements = List("Helium", "Lithium", "Boron", "Carbon", "Oxygen", "Neon", "Sodium", "Silicon",
    "Sulfur", "Argon", "Calcium", "Iron", "Cobalt", "Nickel", "Copper", "Zinc", "Gallium", "Arsenic", "Bromine", "Krypton",
    "Yttrium", "Niobium", "Rhodium", "Silver", "Cadmium", "Indium", "Tin", "Iodine", "Xenon", "Caesium", "Barium", "Cerium",
    "Terbium", "Holmium", "Erbium", "Thulium", "Hafnium", "Rhenium", "Osmium", "Iridium", "Gold", "Mercury", "Lead", "Bismuth",
    "Radon", "Radium", "Thorium", "Uranium", "Curium", "Fermium", "Dubnium", "Bohrium", "Hassium")

  private final val colors = List("Amber", "Apricot", "Aqua", "Auburn", "Azure", "Beige", "Bistre", "Black", "Blue",
    "Brass", "Bronze", "Brown", "Buff", "Carmine", "Celadon", "Cerise", "Cobalt", "Copper", "Coral", "Corn", "Cream",
    "Crimson", "Cyan", "Denim", "Ecru", "Emerald", "Flax", "Fuchsia", "Gamboge", "Gold", "Green", "Grey", "Indigo", "Ivory",
    "Jade", "Khaki", "Lemon", "Lilac", "Lime", "Linen", "Magenta", "Maroon", "Mauve", "Mustard", "Myrtle", "Ochre", "Olive",
    "Olivine", "Orange", "Orchid", "Peach", "Pear", "Pink", "Plum", "Puce", "Pumpkin", "Purple", "Red", "Rose", "Ruby",
    "Russet", "Rust", "Saffron", "Salmon", "Sangria", "Scarlet", "Sepia", "Silver", "Smalt", "Tan", "Taupe", "Teal", "Tawny",
    "Thistle", "Tomato", "Violet", "Wheat", "White", "Xanthic", "Yellow")

  private final val ra:Range = Range(1000, 9999)

  private final val prefix = "FCcredits"

  def randomName:String = {
    val color = colors.apply(nextInt(colors.size))
    val element = elements.apply(nextInt(elements.size))
    val suffix = ra.head + nextInt(ra.end - ra.head)
    s"$prefix-$element-$color-$suffix".replace(" ", "-").toLowerCase
  }

}
