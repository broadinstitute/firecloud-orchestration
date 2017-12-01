package org.broadinstitute.dsde.firecloud.trial

import scala.util.Random.nextInt

object ProjectNamer {

//  private final val elements = List("Hydrogen", "Helium", "Lithium", "Beryllium", "Boron", "Carbon", "Nitrogen", "Oxygen",
//    "Fluorine", "Neon", "Sodium", "Magnesium", "Aluminium", "Silicon", "Phosphorus", "Sulfur", "Chlorine", "Argon", "Potassium",
//    "Calcium", "Scandium", "Titanium", "Vanadium", "Chromium", "Manganese", "Iron", "Cobalt", "Nickel", "Copper", "Zinc",
//    "Gallium", "Germanium", "Arsenic", "Selenium", "Bromine", "Krypton", "Rubidium", "Strontium", "Yttrium", "Zirconium",
//    "Niobium", "Molybdenum", "Technetium", "Ruthenium", "Rhodium", "Palladium", "Silver", "Cadmium", "Indium", "Tin",
//    "Antimony", "Tellurium", "Iodine", "Xenon", "Caesium", "Barium", "Lanthanum", "Cerium", "Praseodymium", "Neodymium",
//    "Promethium", "Samarium", "Europium", "Gadolinium", "Terbium", "Dysprosium", "Holmium", "Erbium", "Thulium", "Ytterbium",
//    "Lutetium", "Hafnium", "Tantalum", "Tungsten", "Rhenium", "Osmium", "Iridium", "Platinum", "Gold", "Mercury", "Thallium",
//    "Lead", "Bismuth", "Polonium", "Astatine", "Radon", "Francium", "Radium", "Actinium", "Thorium", "Protactinium", "Uranium",
//    "Neptunium", "Plutonium", "Americium", "Curium", "Berkelium", "Californium", "Einsteinium", "Fermium", "Mendelevium",
//    "Nobelium", "Lawrencium", "Rutherfordium", "Dubnium", "Seaborgium", "Bohrium", "Hassium", "Meitnerium", "Darmstadtium",
//    "Roentgenium", "Copernicium", "Nihonium", "Flerovium", "Moscovium", "Livermorium", "Tennessine", "Oganesson")

  private final val elements = List("Helium", "Lithium", "Boron", "Carbon", "Oxygen", "Neon", "Sodium", "Silicon",
    "Sulfur", "Argon", "Calcium", "Iron", "Cobalt", "Nickel", "Copper", "Zinc", "Gallium", "Arsenic", "Bromine", "Krypton",
    "Yttrium", "Niobium", "Rhodium", "Silver", "Cadmium", "Indium", "Tin", "Iodine", "Xenon", "Caesium", "Barium", "Cerium",
    "Terbium", "Holmium", "Erbium", "Thulium", "Hafnium", "Rhenium", "Osmium", "Iridium", "Gold", "Mercury", "Lead", "Bismuth",
    "Radon", "Radium", "Thorium", "Uranium", "Curium", "Fermium", "Dubnium", "Bohrium", "Hassium")

//  private final val colors = List( "Alizarin", "Amaranth", "Amber", "Amethyst", "Apricot", "Aqua", "Aquamarine", "Asparagus",
//    "Auburn", "Azure", "Beige", "Bistre", "Black", "Blue", "Blue Green", "Blue Violet", "Bondi Blue", "Brass", "Bronze",
//    "Brown", "Buff", "Burgundy", "Burnt Orange", "Burnt Sienna", "Burnt Umber", "Camouflage Green", "Caput Mortuum",
//    "Cardinal", "Carmine", "Carrot orange", "Celadon", "Cerise", "Cerulean", "Champagne", "Charcoal", "Chartreuse",
//    "Cherry Blossom Pink", "Chestnut", "Chocolate", "Cinnabar", "Cinnamon", "Cobalt", "Copper", "Coral", "Corn", "Cornflower",
//    "Cream", "Crimson", "Cyan", "Dandelion", "Denim", "Ecru", "Eggplant", "Emerald", "Falu red", "Fern green", "Firebrick",
//    "Flax", "Forest green", "French Rose", "Fuchsia", "Gamboge", "Gold", "Goldenrod", "Green", "Grey", "Han Purple",
//    "Harlequin", "Heliotrope", "Hollywood Cerise", "Indigo", "Ivory", "Jade", "Kelly green", "Khaki", "Lavender", "Lawn green",
//    "Lemon", "Lemon chiffon", "Lilac", "Lime", "Lime green", "Linen", "Magenta", "Magnolia", "Malachite", "Maroon", "Mauve",
//    "Midnight Blue", "Mint green", "Misty rose", "Moss green", "Mustard", "Myrtle", "Navajo white", "Navy Blue", "Ochre",
//    "Office green", "Olive", "Olivine", "Orange", "Orchid", "Papaya whip", "Peach", "Pear", "Periwinkle", "Persimmon",
//    "Pine Green", "Pink", "Platinum", "Plum", "Powder blue", "Prussian blue", "Psychedelic purple", "Puce", "Pumpkin",
//    "Purple", "Quartz Grey", "Raw umber", "Razzmatazz", "Red", "Robin egg blue", "Rose", "Royal blue", "Royal purple",
//    "Ruby", "Russet", "Rust", "Safety orange", "Saffron", "Salmon", "Sandy brown", "Sangria", "Sapphire", "Scarlet",
//    "School bus yellow", "Sea Green", "Seashell", "Sepia", "Shamrock green", "Shocking Pink", "Silver", "Sky Blue", "Slate grey",
//    "Smalt", "Spring bud", "Spring green", "Steel blue", "Tan", "Tangerine", "Taupe", "Teal", "Tawny", "Terra cotta",
//    "Thistle", "Titanium White", "Tomato", "Turquoise", "Tyrian purple", "Ultramarine", "Van Dyke Brown", "Vermilion", "Violet",
//    "Viridian", "Wheat", "White", "Wisteria", "Xanthic", "Yellow", "Zucchini")

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

  def trimLists = {
    val shortElements = elements.collect {
      case s if s.length <= 7 => s
    }
    val shortColor = colors.collect {
      case s if s.length <= 7 => s
    }

    println(shortElements)
    println(shortColor)
  }

  def exampleNames = 1 to 10 foreach (_ => println(randomName))


}
