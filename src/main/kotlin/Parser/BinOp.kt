package Parser

data class BinOp(val s : String, val isRightAssoc : Boolean) : INode {
    override fun dump(): String {
        
        var str = this.javaClass.simpleName + "("

        str += s + "; leftAssoc = " + isRightAssoc.toString() + ")"
        return str
    }
}